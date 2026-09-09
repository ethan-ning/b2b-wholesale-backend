package com.acme.b2b.domain.pricing

import com.acme.b2b.types.Money
import com.acme.b2b.types.Quantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.TierId

/**
 * One row of the tier price book: what [tierId] pays for one [sku] at [minQty] or more.
 *
 * Pricing is stated per SKU — there is no SPU-level row to inherit from, because a pack
 * SKU's price is the whole pack and could not be shared with its siblings.
 *
 * [minQty] is always 1 in the MVP: quantity-based pricing is deferred. The field is
 * carried so enabling it later is inserting rows, not changing this type.
 */
data class TierPrice(
    val sku: SkuCode,
    val tierId: TierId,
    val price: Money,
    val minQty: Quantity = Quantity.ONE,
) {
    fun appliesTo(quantity: Quantity): Boolean = minQty.value <= quantity.value
}
