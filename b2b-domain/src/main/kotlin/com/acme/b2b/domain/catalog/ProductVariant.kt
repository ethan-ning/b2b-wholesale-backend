package com.acme.b2b.domain.catalog

import com.acme.b2b.types.Money
import com.acme.b2b.types.PackQuantity
import com.acme.b2b.types.SkuCode

/**
 * A SKU: one purchasable unit beneath a Product. Part of the Product aggregate — it
 * is never loaded or saved on its own.
 *
 * [mapPrice] is stated here rather than on the Product because a pack SKU's advertised
 * price scales with its quantity, so there is no SPU-level figure to inherit.
 */
class ProductVariant(
    val id: Long?,
    val sku: SkuCode,
    /** Value on the parent's axis — "M", "XL", "6". Matches the SKU code's suffix. */
    val variantValue: String?,
    val packQuantity: PackQuantity,
    val mapPrice: Money?,
    val upc: String?,
    val weight: java.math.BigDecimal?,
    /** Sizes are not lexically ordered, so display order is explicit. */
    val sortOrder: Int,
    val active: Boolean,
    val stock: StockLevel,
) {
    /**
     * Per-unit view of a price stated for this SKU. A 6-pack at $93.60 is $15.60/ea —
     * what a dealer compares against buying singles.
     */
    fun perUnit(priceForOneSku: Money): Money = priceForOneSku.dividedBy(packQuantity.value)

    /** MAP is meaningless as a floor if it sits below what the dealer pays. */
    fun mapCoversPrice(price: Money): Boolean = mapPrice == null || mapPrice >= price

    override fun toString() = "ProductVariant($sku)"
}
