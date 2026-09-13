package com.acme.b2b.domain.customer

import com.acme.b2b.types.DiscountPercent
import com.acme.b2b.types.TierId

/**
 * What a group of dealers pays.
 *
 * [discount] is the tier's standing agreement — so much off list, on everything. It is
 * what makes a catalogue priced the moment it is imported, instead of waiting on a figure
 * to be typed for every SKU. A per-SKU row still overrides it where one exists.
 */
data class CustomerTier(
    val id: TierId,
    val name: String,
    val sortOrder: Int,
    val discount: DiscountPercent = DiscountPercent.NONE,
)
