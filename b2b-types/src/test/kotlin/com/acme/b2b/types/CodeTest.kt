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
        assertFailsWith<IllegalArgumentException> { SpuCode("pl001-blk") }   // lower case
        assertFailsWith<IllegalArgumentException> { SkuCode("GL100 BLK M") } // space
    }

    @Test
    fun `a SKU knows the SPU it belongs to`() {
        val spu = SpuCode("GL100-BLK")
        assertTrue(SkuCode("GL100-BLK-M").belongsTo(spu))
        assertFalse(SkuCode("GL100-BRN-M").belongsTo(spu))
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
