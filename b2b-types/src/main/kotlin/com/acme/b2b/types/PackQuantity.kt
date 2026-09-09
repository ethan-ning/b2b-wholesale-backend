package com.acme.b2b.types

/**
 * Units contained in one SKU: 1 for a size-differentiated garment, 6 for a 6-pack.
 * Money on a SKU is stated for one SKU, so this is what converts it to a unit price.
 */
@JvmInline
value class PackQuantity(val value: Int) {
    init { require(value >= 1) { "Pack quantity must be at least 1, was $value" } }

    val isSingle: Boolean get() = value == 1

    override fun toString() = value.toString()

    companion object { val SINGLE = PackQuantity(1) }
}
