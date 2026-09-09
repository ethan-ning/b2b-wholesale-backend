package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.ProductGroupingRepository
import com.acme.b2b.domain.catalog.RegroupedFamily
import com.acme.b2b.domain.catalog.RegroupedSku
import com.acme.b2b.domain.sellfox.SellfoxChild
import com.acme.b2b.domain.sellfox.SellfoxCommodity
import com.acme.b2b.domain.sellfox.SellfoxSkuLinkRepository
import com.acme.b2b.domain.sellfox.SpuGrouping
import com.acme.b2b.domain.sellfox.SyncCounts
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The only place SPU grouping happens.
 *
 * Everything it needs is in `sellfox_sku_link` — what each commodity declared about
 * itself. That is deliberate on both sides: it means grouping can be recomputed without
 * calling Sellfox, and it means the calculation can never come to depend on the answer a
 * previous run gave, which reading pack quantity back off a variant row would do.
 *
 * The import step records those facts and stops. This step decides the structure: which
 * products exist, which SKUs sit under each, and which products the scope no longer
 * covers. Nothing else in the system arranges SKUs into products.
 */
@Component
class SpuRegrouper(
    private val links: SellfoxSkuLinkRepository,
    private val grouping: ProductGroupingRepository,
) {

    fun regroup(counts: SyncCounts): String {
        val recorded = links.findAll()
        counts.read(recorded.size)
        if (recorded.isEmpty()) return "Nothing recorded yet, so there was nothing to group."

        val commodities = recorded.map { link ->
            SellfoxCommodity(
                commodityId = link.commodityId,
                sku = link.sellfoxSku,
                name = link.commodityName,
                fullCid = link.fullCid,
                // Only the id path is used for grouping; the display path is not recorded.
                fullName = "",
                declaredSpu = link.declaredSpu,
                weightGrams = link.weightGrams,
                children = link.baseSellfoxSku
                    ?.let { base -> listOf(SellfoxChild(base, link.baseQuantity ?: 1)) }
                    .orEmpty(),
                // A link only exists for a commodity an import saw in scope and active.
                isActive = true,
            )
        }

        val families = SpuGrouping.group(commodities) { true }.filter { it.hasUsableCode() }
        val outcome = grouping.regroup(families.map { it.toRegrouped() })

        // A product holding none of the SKUs just placed is one the scope no longer
        // covers — the import forgot its links, so nothing here filed anything under it.
        val deactivated = grouping.deactivateProductsNotIn(families.map { it.spuCode }.toSet())

        counts.wrote(outcome.productsCreated + outcome.skusCreated + outcome.skusMoved)

        return buildString {
            append("${families.size} products")
            if (!outcome.changed && deactivated == 0) {
                append("; grouping already correct.")
            } else {
                append(": ${outcome.productsCreated} new")
                append(", ${outcome.skusCreated} SKUs added")
                if (outcome.skusMoved > 0) append(", ${outcome.skusMoved} re-filed")
                if (outcome.emptyProductsRemoved > 0) append(", ${outcome.emptyProductsRemoved} emptied removed")
                if (deactivated > 0) append(", $deactivated deactivated")
                append(".")
            }
        }
    }

    private fun SpuGrouping.Family.toRegrouped() = RegroupedFamily(
        spuCode = spuCode,
        name = name,
        axis = axis?.toVariantAxis(),
        members = members.mapIndexed { index, member ->
            RegroupedSku(
                sku = member.commodity.sku,
                variantValue = member.variantValue,
                packQuantity = member.packQuantity,
                sortOrder = index,
                weight = member.commodity.weightGrams?.let { grams ->
                    BigDecimal.valueOf(grams).divide(GRAMS_PER_KILO, 3, RoundingMode.HALF_UP)
                },
            )
        },
    )

    private companion object {
        val GRAMS_PER_KILO: BigDecimal = BigDecimal(1000)
    }
}
