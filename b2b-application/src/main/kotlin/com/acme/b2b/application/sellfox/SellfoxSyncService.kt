package com.acme.b2b.application.sellfox

import com.acme.b2b.application.support.UseCaseViolation
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
    private val grouping: ProductGroupingRepository,
    private val links: SellfoxSkuLinkRepository,
    private val runner: SyncRunner,
    private val clock: Clock,
) {

    /**
     * A full run: import what is in scope, hide what has left it, then count what remains.
     *
     * The order is deliberate. Products first, so a SKU imported by this run gets its
     * stock from the same run; deactivation before stock, so a product on its way out is
     * not counted on its way past.
     */
    fun syncFull(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun {
        requireScopeChosen()
        return runner.run(SyncMode.FULL, trigger, triggeredBy) { counts ->
            val now = clock.instant()
            val catalog = importCatalog(counts, now)
            // Reconciles the whole set the import just built. The import files one family
            // at a time and cannot move a SKU that another product still owns, which is
            // what happens whenever the grouping rules change; this is the step that can.
            val regrouped = regroupFromLinks(counts)
            val inventory = applyStock(counts, now)
            "$catalog $regrouped $inventory"
        }
    }

    /**
     * Recomputes the SPU grouping from what is already imported, touching no Sellfox
     * endpoint.
     *
     * Grouping is a calculation over facts a sync already recorded — the declared SPU,
     * the declared pack children, the SKU codes. When the calculation improves, the
     * catalog is wrong in a way that needs no new facts to fix, and a full run would
     * spend two minutes re-paging a catalog that has not changed to arrive at the same
     * inputs.
     *
     * Run at the end of every full sync for the same reason: the import files each
     * family as it is built and cannot move a SKU another product still owns.
     *
     * Not scheduled on its own. Between full runs its inputs do not change, so a cron
     * would only ever confirm the previous answer; what makes it worth running is a
     * change to the grouping rules, which is a deploy rather than an hour of the day.
     */
    fun regroup(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun =
        runner.run(SyncMode.REGROUP, trigger, triggeredBy) { counts -> regroupFromLinks(counts) }

    private fun regroupFromLinks(counts: SyncCounts): String {
        val links = links.findAll()
        counts.read(links.size)
        if (links.isEmpty()) return "Nothing imported yet, so there was nothing to regroup."

        // Rebuilt from the recorded inputs, not from the last grouping's output — reading
        // pack quantity off the variant would make each run depend on the previous answer.
        val commodities = links.map { link ->
            SellfoxCommodity(
                commodityId = link.commodityId,
                sku = link.sellfoxSku,
                name = link.commodityName,
                fullCid = link.fullCid,
                fullName = "",
                declaredSpu = link.declaredSpu,
                weightGrams = null,
                children = link.baseSellfoxSku
                    ?.let { base -> listOf(SellfoxChild(base, link.baseQuantity ?: 1)) }
                    .orEmpty(),
                state = ACTIVE_STATE,
            )
        }

        val families = SpuGrouping.group(commodities) { true }
        val outcome = grouping.regroup(
            families.mapNotNull { family ->
                runCatching { SpuCode(family.spuCode) }.getOrNull() ?: return@mapNotNull null
                RegroupedFamily(
                    spuCode = family.spuCode,
                    name = family.name,
                    axisLabel = when (family.axis) {
                        SpuGrouping.Axis.PACK_QUANTITY -> VariantAxis.PACK_QUANTITY.label
                        SpuGrouping.Axis.SIZE -> VariantAxis.SIZE.label
                        null -> null
                    },
                    members = family.members.mapIndexed { index, member ->
                        RegroupedSku(
                            sku = member.commodity.sku,
                            variantValue = member.variantValue,
                            packQuantity = member.packQuantity,
                            sortOrder = index,
                        )
                    },
                )
            }
        )
        counts.wrote(outcome.skusMoved + outcome.productsCreated)

        return if (!outcome.changed) "${families.size} products; grouping already correct."
        else "${families.size} products: ${outcome.productsCreated} new, " +
            "${outcome.skusMoved} SKUs re-filed, ${outcome.emptyProductsRemoved} emptied products removed."
    }

    /**
     * Stock only. Cheap enough to run hourly, which is the point: stock is the part that
     * moves between catalog changes, and a full run has to page every commodity Sellfox
     * holds to find the handful that changed.
     */
    fun syncInventory(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun {
        requireScopeChosen()
        return runner.run(SyncMode.INVENTORY, trigger, triggeredBy) { counts ->
            applyStock(counts, clock.instant())
        }
    }

    /**
     * Refuses a run that would import nothing.
     *
     * Both selections are required, not either: a category with no warehouse imports
     * products that read as out of stock, and a warehouse with no category counts a
     * catalog that is not there. Neither is a state worth spending two minutes reaching.
     *
     * The exception is a first run, when there is nothing to choose from yet — that run
     * is how the lists get filled, so it is allowed to import nothing on purpose.
     *
     * Public because a caller that dispatches the sync to another thread has to ask
     * *before* dispatching. An exception thrown on that thread has nowhere to go, and the
     * caller would report a run that never started.
     */
    fun requireScopeChosen() {
        val knownCategories = scope.categories()
        val knownWarehouses = scope.warehouses()
        if (knownCategories.isEmpty() && knownWarehouses.isEmpty()) return

        val missing = buildList {
            if (knownCategories.isNotEmpty() && scope.selectedCategoryIds().isEmpty()) add("a category")
            if (knownWarehouses.isNotEmpty() && scope.selectedWarehouseIds().isEmpty()) add("a warehouse")
        }
        if (missing.isNotEmpty()) {
            throw UseCaseViolation("Select ${missing.joinToString(" and ")} to sync")
        }
    }

    // ─── Products ────────────────────────────────────────────────────────

    private fun importCatalog(counts: SyncCounts, now: Instant): String {
        val commodities = catalogSource.listCommodities()
        counts.read(commodities.size)

        scope.refreshCategories(discoverCategories(commodities), now)

        val selected = scope.selectedCategoryIds()
        if (selected.isEmpty()) {
            counts.skipped(commodities.size)
            return "Discovered ${scope.categories().size} category groups; none selected, so nothing imported."
        }

        val families = SpuGrouping.group(commodities) { groupKeyOf(it.fullCid) in selected }
        val outcome = families.map { it to importFamily(it, now) }

        // Anything Sellfox-sourced this run did not see has left the scope — its category
        // was deselected, or the supplier dropped it. Hidden rather than deleted: the
        // tier pricing an admin set hangs off these rows, and a category removed by
        // mistake would otherwise cost all of it.
        val imported = outcome
            .filter { (_, result) -> result != Outcome.FAILED }
            .mapNotNull { (family, _) -> runCatching { SpuCode(family.spuCode) }.getOrNull() }
            .toSet()
        val deactivated = products.deactivateSyncedProductsNotIn(imported)

        counts.wrote(outcome.count { (_, result) -> result != Outcome.FAILED })
        counts.skipped(commodities.size - families.sumOf { it.members.size })

        val created = outcome.count { (_, result) -> result == Outcome.CREATED }
        val updated = outcome.count { (_, result) -> result == Outcome.UPDATED }
        val deferred = outcome.count { (_, result) -> result == Outcome.DEFERRED }
        val rejected = outcome.filter { (_, result) -> result == Outcome.FAILED }.map { (family, _) -> family.spuCode }

        return buildString {
            append("${families.size} products from ${selected.size} categor")
            append(if (selected.size == 1) "y" else "ies")
            append(" ($created new, $updated updated")
            if (deferred > 0) append(", $deferred regrouped")
            if (deactivated > 0) append(", $deactivated deactivated")
            if (rejected.isNotEmpty()) {
                // Named, not just counted. "15 could not be built" tells an admin
                // something is wrong and nothing about which product line to go and look
                // at; the codes are the only part that is actionable.
                append(", ${rejected.size} rejected: ")
                append(rejected.take(REJECTS_NAMED).joinToString(", "))
                if (rejected.size > REJECTS_NAMED) append(" and ${rejected.size - REJECTS_NAMED} more")
            }
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
            .groupBy { groupKeyOf(it.fullCid) }
            .map { (key, rows) ->
                SellfoxCategoryScope(
                    cid = key,
                    fullCid = key,
                    fullName = groupNameOf(rows.first().fullName),
                    // Everything beneath the group, since that is what selecting it takes.
                    commodityCount = rows.size,
                )
            }

    /**
     * The group a commodity belongs to: the first two levels of its path.
     * "100010-100020-100030-" groups under "100010-100020". A one-level path is its own
     * group rather than being dropped — "未分类" has nothing beneath it and still holds
     * commodities someone may want.
     */
    private fun groupKeyOf(fullCid: String): String =
        fullCid.trim('-').split('-').take(GROUP_DEPTH).joinToString("-")

    /** "供应商甲/重卡配件/轮毂盖" reads as "供应商甲/重卡配件". */
    private fun groupNameOf(fullName: String): String =
        fullName.split('/').take(GROUP_DEPTH).joinToString("/")

    private fun importFamily(family: SpuGrouping.Family, now: Instant): Outcome {
        // Recorded first, and whatever happens to the product. These are the facts the
        // commodity stated, and a regroup starts from them — a family that defers its
        // filing must not also defer saying what it knows.
        family.members.forEach { member ->
            val child = member.commodity.children.singleOrNull()
            links.save(
                SellfoxSkuLink(
                    sellfoxSku = member.commodity.sku,
                    commodityId = member.commodity.commodityId,
                    fullCid = member.commodity.fullCid,
                    declaredSpu = member.commodity.declaredSpu,
                    baseSellfoxSku = child?.sku,
                    baseQuantity = child?.quantity,
                    commodityName = member.commodity.name,
                    lastSeenAt = now,
                )
            )
        }

        val product = try {
            buildProduct(family, now)
        } catch (ex: IllegalArgumentException) {
            // A SKU the domain refuses — an unexpected character, most likely. Counted
            // rather than thrown: one bad row must not abandon the other six thousand,
            // and the run's summary reports how many were dropped.
            return Outcome.FAILED
        }

        // A SKU sits under exactly one product. If one of these is still filed elsewhere
        // — which is what a changed grouping rule leaves behind — inserting it would trip
        // the unique key and abandon the run, so the regroup step re-files it instead.
        val elsewhere = products.skusFiledElsewhere(
            product.spuCode,
            product.variants.map { it.sku.value }.toSet(),
        )
        if (elsewhere.isNotEmpty()) return Outcome.DEFERRED

        val existing = products.findBySpuCode(product.spuCode)
        if (existing == null) products.create(product) else products.saveSynced(product, existing)
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

    private enum class Outcome {
        CREATED,
        UPDATED,
        /** Left to the regroup step, which is the only path that can move a SKU. */
        DEFERRED,
        FAILED,
    }

    private companion object {
        val GRAMS_PER_KILO: BigDecimal = BigDecimal(1000)

        /** Categories are chosen two levels down — see SellfoxCategoryScope. */
        const val GROUP_DEPTH = 2

        /** Enough to act on; the run summary is a line, not a report. */
        const val REJECTS_NAMED = 8

        /** Sellfox's active lifecycle state — everything reaching a regroup is already it. */
        const val ACTIVE_STATE = "1"
    }
}
