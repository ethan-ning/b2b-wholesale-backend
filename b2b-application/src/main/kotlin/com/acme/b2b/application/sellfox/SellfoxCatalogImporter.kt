package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductRepository
import com.acme.b2b.domain.catalog.ProductStatus
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.domain.catalog.StockLevel
import com.acme.b2b.domain.sellfox.*
import com.acme.b2b.types.Money
import com.acme.b2b.types.PackQuantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Turns the commodities in the selected categories into products.
 *
 * Also rebuilds the category registry from what it saw, because Sellfox exposes no
 * category endpoint — every commodity carries the full path of ids and names, so the tree
 * is only recoverable from the commodities themselves. That is why a first run on a fresh
 * install imports nothing and simply fills the picker.
 */
@Component
class SellfoxCatalogImporter(
    private val sellfox: SellfoxCatalogPort,
    private val scope: SellfoxScopeRepository,
    private val products: ProductRepository,
    private val links: SellfoxSkuLinkRepository,
) {

    fun import(counts: SyncCounts, now: Instant): String {
        val commodities = sellfox.listCommodities()
        counts.read(commodities.size)

        scope.refreshCategories(discoverCategories(commodities), now)

        val selected = scope.selectedCategoryIds()
        if (selected.isEmpty()) {
            counts.skipped(commodities.size)
            return "Discovered ${scope.categories().size} category groups; none selected, so nothing imported."
        }

        val families = SpuGrouping.group(commodities) { groupKeyOf(it.fullCid) in selected }
        val outcome = families.associateWith { importFamily(it, now) }

        // Anything Sellfox-sourced this run did not see has left the scope — its category
        // was deselected, or the supplier dropped it.
        val kept = outcome
            .filterValues { it != Outcome.FAILED }
            .keys.filter { it.hasUsableCode() }
            .map { SpuCode(it.spuCode) }
            .toSet()
        val deactivated = products.deactivateSyncedProductsNotIn(kept)

        counts.wrote(outcome.count { (_, result) -> result != Outcome.FAILED })
        counts.skipped(commodities.size - families.sumOf { it.members.size })

        return summarise(families.size, selected.size, outcome, deactivated)
    }

    private fun summarise(
        familyCount: Int,
        categoryCount: Int,
        outcome: Map<SpuGrouping.Family, Outcome>,
        deactivated: Int,
    ): String {
        val rejected = outcome.filterValues { it == Outcome.FAILED }.keys.map { it.spuCode }
        return buildString {
            append("$familyCount products from $categoryCount categor")
            append(if (categoryCount == 1) "y" else "ies")
            append(" (${outcome.count { it.value == Outcome.CREATED }} new")
            append(", ${outcome.count { it.value == Outcome.UPDATED }} updated")
            outcome.count { it.value == Outcome.DEFERRED }.takeIf { it > 0 }?.let { append(", $it regrouped") }
            deactivated.takeIf { it > 0 }?.let { append(", $it deactivated") }
            if (rejected.isNotEmpty()) {
                // Named, not just counted: "15 could not be built" says something is wrong
                // and nothing about which product line to go and look at.
                append(", ${rejected.size} rejected: ")
                append(rejected.take(REJECTS_NAMED).joinToString(", "))
                if (rejected.size > REJECTS_NAMED) append(" and ${rejected.size - REJECTS_NAMED} more")
            }
            append(").")
        }
    }

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
     * The group a commodity belongs to: the first two levels of its path, so
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
        // commodity stated and a regroup starts from them, so a family that defers its
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
            // A SKU the catalog refuses. Counted rather than thrown: one bad row must not
            // abandon the other six thousand, and the summary names what was dropped.
            return Outcome.FAILED
        }

        // A SKU sits under exactly one product, so a changed grouping means moving it, not
        // inserting it — which would trip the unique key and abandon the run.
        val filedElsewhere = products.skusFiledElsewhere(
            product.spuCode,
            product.variants.map { it.sku.value }.toSet(),
        )
        if (filedElsewhere.isNotEmpty()) return Outcome.DEFERRED

        val existing = products.findBySpuCode(product.spuCode)
        if (existing == null) products.create(product) else products.saveSynced(product, existing)
        return if (existing == null) Outcome.CREATED else Outcome.UPDATED
    }

    private fun buildProduct(family: SpuGrouping.Family, now: Instant) = Product(
        id = null,
        spuCode = SpuCode(family.spuCode),
        name = family.name,
        brand = null,
        description = null,
        // No dealer price exists yet. Importing at zero and leaving the product INACTIVE is
        // what keeps an unpriced product out of the catalog; the admin prices it and turns
        // it on.
        baseWholesalePrice = Money.ZERO,
        locationCode = null,
        variantAxis = family.axis?.toVariantAxis(),
        attributes = emptyMap(),
        status = ProductStatus.INACTIVE,
        categoryIds = emptyList(),
        primaryCategoryId = null,
        imageUrls = emptyList(),
        variants = family.members.mapIndexed { index, member ->
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
                // Filled in by the stock step of this same run.
                stock = StockLevel.empty(now),
            )
        },
    )

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
    }
}
