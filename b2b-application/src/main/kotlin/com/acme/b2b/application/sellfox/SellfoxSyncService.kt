package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.sellfox.*
import com.acme.b2b.types.Money
import com.acme.b2b.types.PackQuantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.VariantAxis
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/**
 * The Sellfox sync: import the products in the selected categories, then count them in
 * the selected warehouses.
 *
 * One run, in that order, because the order matters — a SKU imported by the first half
 * gets its stock from the second half of the same run. Splitting the two meant a newly
 * imported product sat at zero until a separate job happened to come round, which reads
 * to a dealer as out of stock.
 *
 * Both halves are scoped by what the admin selected, and neither does anything until
 * something is. On a fresh install a run discovers the categories and warehouses,
 * imports nothing, and leaves both lists ready to choose from — safe by construction
 * rather than by warning.
 *
 * The catalog half is the expensive one: Sellfox's commodity endpoint accepts no category
 * filter — cid, categoryId, fullCid and cids were each tried against the live service and
 * each returned the whole catalog — so scoping happens here, after paging every row. At
 * ~6,400 commodities that is around two minutes, and it sets the floor on how often this
 * can reasonably run.
 */
@Service
class SellfoxSyncService(
    private val catalogSource: SellfoxCatalogPort,
    private val inventorySource: SellfoxInventoryPort,
    private val scope: SellfoxScopeRepository,
    private val products: ProductRepository,
    private val stock: ProductStockRepository,
    private val links: SellfoxSkuLinkRepository,
    private val runner: SyncRunner,
    private val clock: Clock,
) {

    fun sync(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun =
        runner.run(trigger, triggeredBy) { counts ->
            val now = clock.instant()
            val catalog = importCatalog(counts, now)
            val inventory = applyStock(counts, now)
            "$catalog $inventory"
        }

    // ─── Products ────────────────────────────────────────────────────────

    private fun importCatalog(counts: SyncCounts, now: Instant): String {
        val commodities = catalogSource.listCommodities()
        counts.read(commodities.size)

        scope.refreshCategories(discoverCategories(commodities), now)

        val selected = scope.selectedCategoryIds()
        if (selected.isEmpty()) {
            counts.skipped(commodities.size)
            return "Discovered ${scope.categories().size} categories; none selected, so nothing imported."
        }

        val families = SpuGrouping.group(commodities) { leafCidOf(it.fullCid) in selected }
        val outcome = families.map { importFamily(it, now) }

        counts.wrote(outcome.count { it != Outcome.FAILED })
        counts.skipped(commodities.size - families.sumOf { it.members.size })

        val created = outcome.count { it == Outcome.CREATED }
        val updated = outcome.count { it == Outcome.UPDATED }
        val failed = outcome.count { it == Outcome.FAILED }
        return buildString {
            append("${families.size} products from ${selected.size} categor")
            append(if (selected.size == 1) "y" else "ies")
            append(" ($created new, $updated updated")
            if (failed > 0) append(", $failed could not be built")
            append(").")
        }
    }

    /**
     * The category registry, rebuilt from what the scan saw. Sellfox exposes no category
     * endpoint, but every commodity carries the full path of ids and names, so the tree
     * is recoverable from the commodities themselves.
     */
    private fun discoverCategories(commodities: List<SellfoxCommodity>): List<SellfoxCategoryScope> =
        commodities
            .filter { it.fullCid.isNotBlank() }
            .groupBy { it.fullCid }
            .map { (fullCid, rows) ->
                SellfoxCategoryScope(
                    cid = leafCidOf(fullCid),
                    fullCid = fullCid,
                    fullName = rows.first().fullName,
                    commodityCount = rows.size,
                )
            }

    /** "100010-100020-100030-" identifies its leaf, 100030 — the id an admin selects. */
    private fun leafCidOf(fullCid: String): String = fullCid.trim('-').substringAfterLast('-')

    private fun importFamily(family: SpuGrouping.Family, now: Instant): Outcome {
        val product = try {
            buildProduct(family, now)
        } catch (ex: IllegalArgumentException) {
            // A SKU the domain refuses — an unexpected character, most likely. Counted
            // rather than thrown: one bad row must not abandon the other six thousand,
            // and the run's summary reports how many were dropped.
            return Outcome.FAILED
        }

        val existing = products.findBySpuCode(product.spuCode)
        if (existing == null) products.create(product) else products.saveSynced(product, existing)

        val baseSku = family.members.firstOrNull { it.isBase }?.commodity?.sku
        family.members.forEach { member ->
            links.save(
                SellfoxSkuLink(
                    sellfoxSku = member.commodity.sku,
                    commodityId = member.commodity.commodityId,
                    fullCid = member.commodity.fullCid,
                    baseSellfoxSku = if (member.isBase) null else baseSku,
                    lastSeenAt = now,
                )
            )
        }
        return if (existing == null) Outcome.CREATED else Outcome.UPDATED
    }

    private fun buildProduct(family: SpuGrouping.Family, now: Instant): Product {
        val variants = family.members.mapIndexed { index, member ->
            ProductVariant(
                id = null,
                sku = SkuCode(member.commodity.sku),
                variantValue = member.variantValue,
                packQuantity = PackQuantity(member.packQuantity),
                // MAP and price are the portal's to set; Sellfox has neither.
                mapPrice = null,
                upc = null,
                weight = member.commodity.weightGrams?.let { grams ->
                    BigDecimal.valueOf(grams).divide(GRAMS_PER_KILO, 3, RoundingMode.HALF_UP)
                },
                sortOrder = index,
                active = true,
                // Filled in by the stock half of this same run.
                stock = StockLevel.empty(now),
            )
        }

        return Product(
            id = null,
            spuCode = SpuCode(family.spuCode),
            name = family.name,
            brand = null,
            description = null,
            // No dealer price exists yet. Importing at zero and leaving the product
            // INACTIVE is what keeps an unpriced product out of the catalog; the admin
            // prices it and turns it on.
            baseWholesalePrice = Money.ZERO,
            locationCode = null,
            variantAxis = when (family.axis) {
                SpuGrouping.Axis.PACK_QUANTITY -> VariantAxis.PACK_QUANTITY
                SpuGrouping.Axis.SIZE -> VariantAxis.SIZE
                null -> null
            },
            attributes = emptyMap(),
            status = ProductStatus.INACTIVE,
            categoryIds = emptyList(),
            primaryCategoryId = null,
            imageUrls = emptyList(),
            variants = variants,
        )
    }

    // ─── Stock ───────────────────────────────────────────────────────────

    /**
     * Stock is summed across the selected warehouses. A variant carries one available
     * figure and a dealer only asks whether it can ship; which warehouse it ships from is
     * a fulfilment concern the portal does not surface. Summing over *selected*
     * warehouses is what keeps stock that cannot reach a US dealer out of the number.
     */
    private fun applyStock(counts: SyncCounts, now: Instant): String {
        scope.refreshWarehouses(inventorySource.listWarehouses(), now)

        val warehouseIds = scope.selectedWarehouseIds()
        if (warehouseIds.isEmpty()) {
            return "Discovered ${scope.warehouses().size} warehouses; none selected, so no stock read."
        }

        val totals = mutableMapOf<String, Totals>()
        warehouseIds.forEach { warehouseId ->
            val rows = inventorySource.listStock(warehouseId)
            counts.read(rows.size)
            rows.forEach { row -> totals.getOrPut(row.sku) { Totals() }.add(row.available, row.incoming) }
        }

        val matched = stock.applyStock(
            totals.map { (sku, t) -> SkuStockUpdate(sku, t.available, t.incoming, now) }
        )
        counts.wrote(matched)
        // Sellfox holds far more SKUs than the portal carries; the rest are not errors,
        // they are simply out of catalog.
        counts.skipped(totals.size - matched)

        return "Stock for $matched of ${totals.size} SKUs across ${warehouseIds.size} warehouse" +
            (if (warehouseIds.size == 1) "." else "s.")
    }

    private class Totals {
        var available = 0; private set
        var incoming = 0; private set
        fun add(available: Int, incoming: Int) {
            this.available += available
            this.incoming += incoming
        }
    }

    private enum class Outcome { CREATED, UPDATED, FAILED }

    private companion object {
        val GRAMS_PER_KILO: BigDecimal = BigDecimal(1000)
    }
}
