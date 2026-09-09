package com.acme.b2b.domain.catalog

import com.acme.b2b.domain.ProductFixtures.product
import com.acme.b2b.domain.ProductFixtures.variant
import com.acme.b2b.types.SkuCode
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
    fun `accepts the grouping the ERP declares, even when codes do not nest`() {
        // Sellfox files "AX-K210-ZN-4 S" under the SPU "AX-K210-ZN S": the pack count
        // sits before the suffix, so the SKU does not start with the SPU code. The ERP
        // owns grouping, so requiring its codes to nest would reject what it declared.
        val product = product(
            spuCode = "AX-K210-ZN S",
            variants = listOf(
                variant("AX-K210-ZN-4 S", "4"),
                variant("AX-K210-ZN-6 S", "6"),
            ),
            axis = VariantAxis.PACK_QUANTITY,
        )

        assertEquals(2, product.variants.size)
    }

    @Test
    fun `rejects the same SKU twice in one product`() {
        val error = assertFailsWith<IllegalArgumentException> {
            product(
                variants = listOf(
                    variant("GL100-BLK-M", "M"),
                    variant("GL100-BLK-M", "M"),
                ),
                axis = VariantAxis.SIZE,
            )
        }
        assertTrue(error.message!!.contains("Duplicate"), error.message)
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
    fun `a product is sellable only when every SKU is priced`() {
        val gloves = product(
            variants = listOf(variant("GL100-BLK-S", "S"), variant("GL100-BLK-M", "M")),
            axis = VariantAxis.SIZE,
        )

        assertTrue(gloves.isSellable(setOf(SkuCode("GL100-BLK-S"), SkuCode("GL100-BLK-M"))))
        // Half-priced shows one size at list price and the other at nothing, which reads
        // to the dealer as a broken page rather than as a missing price.
        assertFalse(gloves.isSellable(setOf(SkuCode("GL100-BLK-S"))))
        assertFalse(gloves.isSellable(emptySet()))
    }

    @Test
    fun `a discontinued SKU does not have to be priced`() {
        // The supplier stopped selling it, so it is not something to offer — and it must
        // not hold the rest of the product back from going on sale.
        val gloves = product(
            variants = listOf(
                variant("GL100-BLK-S", "S"),
                variant("GL100-BLK-M", "M").let {
                    ProductVariant(
                        it.id, it.sku, it.variantValue, it.packQuantity, it.mapPrice, it.upc,
                        it.weight, it.sortOrder, active = false, stock = it.stock,
                    )
                },
            ),
            axis = VariantAxis.SIZE,
        )

        assertTrue(gloves.isSellable(setOf(SkuCode("GL100-BLK-S"))))
    }

    @Test
    fun `a product whose every SKU is discontinued is not sellable`() {
        val gone = product(
            variants = listOf(
                variant("GL100-BLK-S", "S").let {
                    ProductVariant(
                        it.id, it.sku, it.variantValue, it.packQuantity, it.mapPrice, it.upc,
                        it.weight, it.sortOrder, active = false, stock = it.stock,
                    )
                },
            ),
        )

        assertFalse(gone.isSellable(setOf(SkuCode("GL100-BLK-S"))))
    }

    @Test
    fun `a freshly imported product is not sellable`() {
        // What an ERP import looks like: real SKUs, no tier prices anywhere.
        assertFalse(product().isSellable(emptySet()))
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
