package com.acme.b2b.domain.catalog

import com.acme.b2b.domain.ProductFixtures.product
import com.acme.b2b.domain.ProductFixtures.variant
import com.acme.b2b.types.VariantAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The aggregate's invariants. No Spring context and no database — which is the point of
 * keeping the domain framework-free.
 */
class ProductTest {

    @Test
    fun `rejects a SKU that does not sit beneath the product`() {
        val error = assertFailsWith<IllegalArgumentException> {
            product(spuCode = "GL100-BLK", variants = listOf(variant("JK400-BLK-M", "M")))
        }
        assertTrue(error.message!!.contains("JK400-BLK-M"))
    }

    @Test
    fun `rejects duplicate SKUs`() {
        assertFailsWith<IllegalArgumentException> {
            product(variants = listOf(variant("GL100-BLK-M", "M"), variant("GL100-BLK-M", "M")))
        }
    }

    @Test
    fun `a multi-SKU product must declare its variant axis`() {
        assertFailsWith<IllegalArgumentException> {
            product(
                axis = null,
                variants = listOf(variant("GL100-BLK-S", "S"), variant("GL100-BLK-M", "M")),
            )
        }
    }

    @Test
    fun `a single-SKU product needs no axis`() {
        val single = product(axis = null, variants = listOf(variant("GL100-BLK-M", "M")))
        assertEquals(1, single.variants.size)
    }

    @Test
    fun `orders variants by sort order, because sizes are not lexical`() {
        val jacket = product(
            spuCode = "JK400-BLK",
            axis = VariantAxis.SIZE,
            variants = listOf(
                variant("JK400-BLK-XL", "XL", sortOrder = 3),
                variant("JK400-BLK-S", "S", sortOrder = 0),
                variant("JK400-BLK-L", "L", sortOrder = 2),
                variant("JK400-BLK-M", "M", sortOrder = 1),
            ),
        )
        assertEquals(listOf("S", "M", "L", "XL"), jacket.variants.map { it.variantValue })
    }

    @Test
    fun `reports stock across its SKUs`() {
        val gloves = product(
            variants = listOf(
                variant("GL100-BLK-S", "S", available = 0),
                variant("GL100-BLK-M", "M", available = 4),
            ),
        )
        assertEquals(4, gloves.totalAvailableStock)
        assertTrue(gloves.hasStock)
        assertTrue(gloves.requireVariant(gloves.variants.first().sku).stock.isOutOfStock)
        assertFalse(gloves.variants.last().stock.isOutOfStock)
    }

    @Test
    fun `unfiling a category hands the primary slot to another`() {
        val gloves = product(categoryIds = listOf(21, 22), primaryCategoryId = 22)

        val unfiled = gloves.withoutCategory(22)

        assertEquals(listOf(21L), unfiled.categoryIds)
        assertEquals(21L, unfiled.primaryCategoryId)
    }

    @Test
    fun `unfiling a non-primary category leaves the primary alone`() {
        val gloves = product(categoryIds = listOf(21, 22), primaryCategoryId = 21)

        val unfiled = gloves.withoutCategory(22)

        assertEquals(listOf(21L), unfiled.categoryIds)
        assertEquals(21L, unfiled.primaryCategoryId)
    }

    @Test
    fun `unfiling the last category leaves the product filed nowhere`() {
        val gloves = product(categoryIds = listOf(22), primaryCategoryId = 22)

        val unfiled = gloves.withoutCategory(22)

        assertTrue(unfiled.categoryIds.isEmpty())
        assertNull(unfiled.primaryCategoryId)
        // The product itself is untouched — a category is a shelf, not the stock on it.
        assertEquals(gloves.spuCode, unfiled.spuCode)
        assertEquals(gloves.variants.size, unfiled.variants.size)
    }
}
