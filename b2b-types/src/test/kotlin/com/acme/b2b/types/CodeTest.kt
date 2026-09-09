package com.acme.b2b.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeTest {

    @Test
    fun `rejects malformed codes at construction`() {
        assertFailsWith<IllegalArgumentException> { SpuCode("") }
        assertFailsWith<IllegalArgumentException> { SpuCode("pl001-blk") }    // lower case
        assertFailsWith<IllegalArgumentException> { SkuCode(" GL100-BLK") }   // leading space
        assertFailsWith<IllegalArgumentException> { SkuCode("GL100-BLK ") }   // trailing space
    }

    @Test
    fun `accepts the spaces real supplier codes carry`() {
        // "AX-K210-ZN-4" and "AX-K210-ZN-4 S" are different products at the supplier.
        // Rejecting the spaced form pushes callers into normalising it, which merges them.
        assertEquals("AX-K210-ZN-4 S", SkuCode("AX-K210-ZN-4 S").value)
        assertEquals("WM7C310J255-QT4 BK", SpuCode("WM7C310J255-QT4 BK").value)
    }

    @Test
    fun `a SKU knows the SPU it belongs to`() {
        val spu = SpuCode("GL100-BLK")
        assertTrue(SkuCode("GL100-BLK-M").belongsTo(spu))
        assertFalse(SkuCode("GL100-BRN-M").belongsTo(spu))
    }

    @Test
    fun `a product's only SKU may be the SPU code itself`() {
        val spu = SpuCode("RB-QF01-S")
        assertTrue(SkuCode("RB-QF01-S").belongsTo(spu))
        assertNull(SkuCode("RB-QF01-S").variantSuffix(spu))   // nothing to vary on
        assertTrue(SkuCode("RB-QF01-S-2P").belongsTo(spu))
        assertEquals("2P", SkuCode("RB-QF01-S-2P").variantSuffix(spu))
    }

    @Test
    fun `the variant suffix is the value on the axis`() {
        val spu = SpuCode("GL100-BLK")
        assertEquals("M", SkuCode("GL100-BLK-M").variantSuffix(spu))
        assertEquals("06", SkuCode("GL100-BLK-06").variantSuffix(spu))
        assertNull(SkuCode("JK400-BLK-M").variantSuffix(spu))
    }

    @Test
    fun `pack quantity must be at least one`() {
        assertFailsWith<IllegalArgumentException> { PackQuantity(0) }
        assertTrue(PackQuantity.SINGLE.isSingle)
    }
}
