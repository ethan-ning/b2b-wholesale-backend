package com.acme.b2b.types

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A monetary amount, always at 2dp so equality and comparison behave. Single-currency
 * by design — the MVP is USD only; a currency field belongs here when that changes.
 */
class Money private constructor(val amount: BigDecimal) : Comparable<Money> {

    operator fun plus(other: Money) = of(amount + other.amount)
    operator fun minus(other: Money) = of(amount - other.amount)
    operator fun times(factor: Int) = of(amount * factor.toBigDecimal())

    /** Per-unit price of a pack. Used to show "$15.60/ea" beneath a pack total. */
    fun dividedBy(divisor: Int): Money {
        require(divisor > 0) { "Cannot divide money by $divisor" }
        return of(amount.divide(divisor.toBigDecimal(), 2, RoundingMode.HALF_UP))
    }

    /**
     * This price with [discount] taken off, rounded to the cent.
     *
     * Rounded once, here, rather than by each caller: two places rounding the same
     * calculation their own way is how a listing and its basket come to disagree by a
     * penny.
     */
    fun lessDiscount(discount: DiscountPercent): Money =
        if (discount.isNone) this else of(amount * discount.multiplier)

    override fun compareTo(other: Money) = amount.compareTo(other.amount)
    override fun equals(other: Any?) = other is Money && amount.compareTo(other.amount) == 0
    override fun hashCode() = amount.stripTrailingZeros().hashCode()
    override fun toString() = "$" + amount.toPlainString()

    companion object {
        val ZERO = of(BigDecimal.ZERO)

        fun of(amount: BigDecimal): Money {
            require(amount.signum() >= 0) { "Money must not be negative: $amount" }
            return Money(amount.setScale(2, RoundingMode.HALF_UP))
        }

        fun of(amount: String): Money = of(BigDecimal(amount))
        fun of(amount: Double): Money = of(BigDecimal.valueOf(amount))
    }
}
