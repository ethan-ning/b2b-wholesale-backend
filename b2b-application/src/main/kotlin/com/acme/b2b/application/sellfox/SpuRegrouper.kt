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

/**
 * Recomputes which SKUs belong to which product, from what is already imported.
 *
 * Grouping is a calculation over facts a sync recorded — the declared SPU, the declared
 * pack children, the SKU codes — so when the calculation improves, the catalog is wrong
 * in a way that needs no new facts to put right.
 */
@Component
class SpuRegrouper(
    private val links: SellfoxSkuLinkRepository,
    private val grouping: ProductGroupingRepository,
) {

    fun regroup(counts: SyncCounts): String {
        val recorded = links.findAll()
        counts.read(recorded.size)
        if (recorded.isEmpty()) return "Nothing imported yet, so there was nothing to regroup."

        // Rebuilt from the recorded inputs, not from the last grouping's output — reading
        // pack quantity back off the variant would make each run depend on the answer the
        // previous one gave.
        val commodities = recorded.map { link ->
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
                isActive = true,
            )
        }

        val families = SpuGrouping.group(commodities) { true }
        val outcome = grouping.regroup(families.filter { it.hasUsableCode() }.map { it.toRegrouped() })
        counts.wrote(outcome.skusMoved + outcome.productsCreated)

        return if (!outcome.changed) "${families.size} products; grouping already correct."
        else "${families.size} products: ${outcome.productsCreated} new, " +
            "${outcome.skusMoved} SKUs re-filed, ${outcome.emptyProductsRemoved} emptied products removed."
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
            )
        },
    )
}
