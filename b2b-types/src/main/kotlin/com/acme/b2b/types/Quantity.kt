package com.acme.b2b.types

/** A count of SKUs being priced or ordered. */
@JvmInline
value class Quantity(val value: Int) {
    init { require(value >= 1) { "Quantity must be at least 1, was $value" } }

    override fun toString() = value.toString()

    companion object { val ONE = Quantity(1) }
}
