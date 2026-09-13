package com.acme.b2b.types

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How much a tier comes off list, as a percentage.
 *
 * A Domain Primitive: an invalid one cannot exist. Bounded below at zero because a
 * negative discount is a surcharge and nothing here means to express one, and below a
 * hundred because a tier that pays nothing is a giveaway rather than a price.
 */
@JvmInline
value class DiscountPercent private constructor(val value: BigDecimal) : Comparable<DiscountPercent> {

    val isNone: Boolean get() = value.compareTo(BigDecimal.ZERO) == 0

    /** The multiplier to apply to a list price: 18% off is 0.82. */
    val multiplier: BigDecimal get() = BigDecimal.ONE - value.divide(HUNDRED, 6, RoundingMode.HALF_UP)

    override fun compareTo(other: DiscountPercent) = value.compareTo(other.value)

    override fun toString() = "${value.stripTrailingZeros().toPlainString()}%"

    companion object {
        private val HUNDRED = BigDecimal("100")
        val NONE = of(BigDecimal.ZERO)

        fun of(value: BigDecimal): DiscountPercent {
            require(value >= BigDecimal.ZERO) { "A discount cannot be negative: $value" }
            require(value < HUNDRED) { "A discount must be under 100%: $value" }
            return DiscountPercent(value.setScale(2, RoundingMode.HALF_UP))
        }

        fun of(value: String): DiscountPercent = of(BigDecimal(value))
        fun of(value: Int): DiscountPercent = of(value.toBigDecimal())
    }
}
