package com.acme.b2b.domain.customer

import com.acme.b2b.types.DiscountPercent
import com.acme.b2b.types.TierId

/**
 * What a group of dealers pays.
 *
 * Exactly one tier is the [anchor]: the price everything else is worked out from. Its own
 * price is stated per SKU and discounted by nobody; every other tier takes [discount] off
 * it unless that SKU has been given a price of its own.
 */
data class CustomerTier(
    val id: TierId,
    val name: String,
    val sortOrder: Int,
    val discount: DiscountPercent = DiscountPercent.NONE,
    /** The tier whose price the others discount from. True for exactly one. */
    val anchor: Boolean = false,
)
