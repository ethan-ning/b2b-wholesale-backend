package com.acme.b2b.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiscountPercentTest {

    @Test
    fun `a surcharge is not a discount`() {
        assertFailsWith<IllegalArgumentException> { DiscountPercent.of("-1") }
    }

    /** At a hundred percent the dealer pays nothing, which is a giveaway and not a price. */
    @Test
    fun `a discount stops short of the whole price`() {
        assertFailsWith<IllegalArgumentException> { DiscountPercent.of(100) }
        assertFailsWith<IllegalArgumentException> { DiscountPercent.of("100.01") }
        DiscountPercent.of("99.99")
    }

    @Test
    fun `zero is a discount, and knows it is nothing`() {
        assertTrue(DiscountPercent.NONE.isNone)
        assertTrue(DiscountPercent.of("0.00").isNone)
        assertEquals(Money.of("10.00"), Money.of("10.00").lessDiscount(DiscountPercent.NONE))
    }

    @Test
    fun `takes its percentage off a price`() {
        assertEquals(Money.of("82.00"), Money.of("100.00").lessDiscount(DiscountPercent.of(18)))
        assertEquals(Money.of("93.00"), Money.of("100.00").lessDiscount(DiscountPercent.of(7)))
    }

    /**
     * Rounded to the cent, once. A third of a penny either way is what makes a listing and
     * an invoice disagree.
     */
    @Test
    fun `rounds to the cent, half up`() {
        // 9.99 less 7% is 9.2907
        assertEquals(Money.of("9.29"), Money.of("9.99").lessDiscount(DiscountPercent.of(7)))
        // 0.15 less 50% is 0.075, which rounds up rather than to 0.07
        assertEquals(Money.of("0.08"), Money.of("0.15").lessDiscount(DiscountPercent.of(50)))
    }

    @Test
    fun `a fractional discount is kept to two places`() {
        assertEquals("12.35%", DiscountPercent.of("12.345").toString())
        assertEquals(Money.of("87.65"), Money.of("100.00").lessDiscount(DiscountPercent.of("12.345")))
    }

    @Test
    fun `nothing off nothing is still nothing`() {
        assertEquals(Money.ZERO, Money.ZERO.lessDiscount(DiscountPercent.of(18)))
    }
}
