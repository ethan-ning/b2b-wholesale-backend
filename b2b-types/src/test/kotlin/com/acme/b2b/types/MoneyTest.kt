package com.acme.b2b.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `normalises scale so equality is by value`() {
        assertEquals(Money.of("17.1"), Money.of("17.10"))
        assertEquals("$17.10", Money.of("17.1").toString())
    }

    @Test
    fun `rejects negative amounts at construction`() {
        assertFailsWith<IllegalArgumentException> { Money.of("-0.01") }
    }

    @Test
    fun `divides a pack total into a unit price, rounding half up`() {
        assertEquals(Money.of("15.60"), Money.of("93.60").dividedBy(6))
        assertEquals(Money.of("4.33"), Money.of("13.00").dividedBy(3))
    }

    @Test
    fun `multiplies a unit price into a pack total`() {
        assertEquals(Money.of("93.60"), Money.of("15.60") * 6)
    }

    @Test
    fun `compares by amount`() {
        assertTrue(Money.of("39.99") > Money.of("17.10"))
    }
}
