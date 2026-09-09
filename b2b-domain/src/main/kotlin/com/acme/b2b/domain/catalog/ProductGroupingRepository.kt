package com.acme.b2b.domain.catalog

import com.acme.b2b.types.VariantAxis
import java.math.BigDecimal

/**
 * Moves SKUs between products when the grouping changes.
 *
 * Kept off [ProductRepository] because it is not an aggregate operation: no single
 * aggregate can express a variant row leaving one product for another.
 *
 * Safe to do because tier prices key on the SKU rather than the product, so pricing
 * follows a variant across a regroup untouched.
 */
interface ProductGroupingRepository {
    /**
     * Files each SKU under the SPU it now belongs to, creating products that do not yet
     * exist and deleting ERP-sourced ones left holding nothing.
     *
     * Removing an emptied product is not the delete the portal forbids: its SKUs and
     * their pricing have moved to whichever product now owns them, so what goes is a
     * shell that a Product could not legally be anyway, having no variants.
     */
    fun regroup(families: List<RegroupedFamily>): RegroupOutcome

    /**
     * Hides ERP-sourced products left holding no SKU the latest grouping placed.
     *
     * Deactivated rather than deleted: the tier pricing an admin set hangs off those
     * rows, and a category removed by mistake would otherwise cost all of it.
     */
    fun deactivateProductsNotIn(spuCodes: Set<String>): Int
}

data class RegroupedFamily(
    val spuCode: String,
    val name: String,
    /** Null for a family of one — a lone SKU varies along nothing. */
    val axis: VariantAxis?,
    val members: List<RegroupedSku>,
)

data class RegroupedSku(
    val sku: String,
    val variantValue: String?,
    val packQuantity: Int,
    val sortOrder: Int,
    /** Kilograms. Carried because a SKU this run creates has no row to inherit from. */
    val weight: BigDecimal?,
)

data class RegroupOutcome(
    val productsCreated: Int,
    val skusCreated: Int,
    val skusMoved: Int,
    /** SKUs the supplier no longer sells, marked discontinued rather than deleted. */
    val skusWithdrawn: Int,
    val emptyProductsRemoved: Int,
) {
    val changed: Boolean
        get() = productsCreated > 0 || skusCreated > 0 || skusMoved > 0 ||
            skusWithdrawn > 0 || emptyProductsRemoved > 0
}
