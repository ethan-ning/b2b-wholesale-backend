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
    fun `a product is sellable once every SKU on sale has a default price`() {
        val gloves = product(
            variants = listOf(variant("GL100-BLK-S", "S"), variant("GL100-BLK-M", "M")),
            axis = VariantAxis.SIZE,
        )

        assertTrue(gloves.isSellable(setOf(SkuCode("GL100-BLK-S"), SkuCode("GL100-BLK-M"))))
        // Every other tier is worked out from that price, so one SKU short is not "cheap
        // on one size" — it is a size with no price at all.
        assertFalse(gloves.isSellable(setOf(SkuCode("GL100-BLK-S"))))
        assertFalse(gloves.isSellable(emptySet()))
    }

    @Test
    fun `the product's cost has nothing to do with whether it can be sold`() {
        assertTrue(product(basePrice = "0.00").isSellable(setOf(SkuCode("GL100-BLK-M"))))
    }

    /** The same SKU as the supplier would leave it after withdrawing it. */
    private fun withdrawn(v: ProductVariant) = ProductVariant(
        v.id, v.sku, v.variantValue, v.packQuantity, v.mapPrice, v.upc,
        v.weight, v.sortOrder, active = false, stock = v.stock,
    )

    @Test
    fun `a dealer is offered only the SKUs still on sale`() {
        val gloves = product(
            variants = listOf(
                variant("GL100-BLK-S", "S"),
                withdrawn(variant("GL100-BLK-M", "M")),
            ),
            axis = VariantAxis.SIZE,
        )

        // The withdrawn one stays on the product so its pricing survives, but showing it
        // would be offering to sell something that cannot be bought.
        assertEquals(2, gloves.variants.size)
        assertEquals(listOf("GL100-BLK-S"), gloves.onSaleVariants.map { it.sku.value })
    }

    @Test
    fun `a withdrawn SKU does not hold the rest of the product back`() {
        val gloves = product(
            variants = listOf(
                variant("GL100-BLK-S", "S"),
                withdrawn(variant("GL100-BLK-M", "M")),
            ),
            axis = VariantAxis.SIZE,
        )

        assertTrue(gloves.isSellable(setOf(SkuCode("GL100-BLK-S"))))
    }

    @Test
    fun `a product whose every SKU is discontinued is not sellable`() {
        val gone = product(
            variants = listOf(
                withdrawn(variant("GL100-BLK-S", "S")),
            ),
        )

        assertFalse(gone.isSellable(setOf(SkuCode("GL100-BLK-S"))))
    }

    @Test
    fun `a freshly imported product is not sellable until its SKUs are priced`() {
        // An import arrives with no prices at all, and nothing derives one for it.
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
