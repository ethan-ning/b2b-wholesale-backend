package com.acme.b2b.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmailTest {

    @Test
    fun `normalises case and surrounding space`() {
        assertEquals("dealer@example.com", Email.of("  Dealer@Example.COM ").value)
    }

    @Test
    fun `rejects addresses that cannot be one`() {
        assertFailsWith<IllegalArgumentException> { Email.of("") }
        assertFailsWith<IllegalArgumentException> { Email.of("no-at-sign") }
        assertFailsWith<IllegalArgumentException> { Email.of("no@domain") }
        assertFailsWith<IllegalArgumentException> { Email.of("two@@example.com") }
    }

    @Test
    fun `rejects passwords outside the usable range`() {
        assertFailsWith<IllegalArgumentException> { RawPassword("short") }
        assertFailsWith<IllegalArgumentException> { RawPassword("x".repeat(73)) }
        RawPassword("longenough")
    }
}
