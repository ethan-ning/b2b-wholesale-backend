package com.acme.b2b.application.catalog

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.ProductSort
import com.acme.b2b.domain.catalog.ProductSortField
import com.acme.b2b.domain.catalog.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortParserTest {

    @Test
    fun `defaults to SPU code ascending`() {
        assertEquals(ProductSort(ProductSortField.SPU_CODE, SortDirection.ASC), SortParser.parse(null, null))
        assertEquals(ProductSort(ProductSortField.SPU_CODE, SortDirection.ASC), SortParser.parse("", "desc"))
    }

    @Test
    fun `accepts the fields the admin list offers, in either direction`() {
        assertEquals(ProductSortField.BRAND, SortParser.parse("brand", "asc").field)
        assertEquals(ProductSortField.PRICE, SortParser.parse("price", "descend").field)
        assertEquals(SortDirection.DESC, SortParser.parse("spuCode", "desc").direction)
        assertEquals(ProductSortField.SPU_CODE, SortParser.parse("SPUCODE", null).field)
    }

    @Test
    fun `still understands the dealer portal's combined tokens`() {
        assertEquals(ProductSort(ProductSortField.PRICE, SortDirection.ASC), SortParser.parse("price_asc", null))
        assertEquals(ProductSort(ProductSortField.PRICE, SortDirection.DESC), SortParser.parse("price_desc", null))
        assertEquals(ProductSort(ProductSortField.NAME, SortDirection.ASC), SortParser.parse("relevance", null))
    }

    @Test
    fun `rejects an unknown field rather than falling back`() {
        // A silent fallback makes a typo look like a broken sort control.
        val error = assertFailsWith<UseCaseViolation> { SortParser.parse("colour", null) }
        assertTrue(error.message!!.contains("Cannot sort by 'colour'"))
        assertFailsWith<UseCaseViolation> { SortParser.parse("brand", "sideways") }
    }
}
