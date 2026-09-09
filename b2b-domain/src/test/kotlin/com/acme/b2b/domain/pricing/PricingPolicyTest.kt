package com.acme.b2b.domain.pricing

import com.acme.b2b.domain.ProductFixtures.product
import com.acme.b2b.domain.ProductFixtures.variant
import com.acme.b2b.types.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricingPolicyTest {

    private val gold = TierId(1)
    private val silver = TierId(2)

    @Test
    fun `uses the SKU's tier row`() {
        val gloves = product(variants = listOf(variant("GL100-BLK-M", "M")))
        val sku = gloves.variants.single()
        val book = listOf(
            TierPrice(sku.sku, gold, Money.of("14.00")),
            TierPrice(sku.sku, silver, Money.of("16.50")),
        )

        assertEquals(Money.of("14.00"), PricingPolicy.resolve(gloves, sku, gold, book).forOneSku)
        assertEquals(Money.of("16.50"), PricingPolicy.resolve(gloves, sku, silver, book).forOneSku)
    }

    @Test
    fun `a pack SKU is priced for the whole pack, and reports its unit price`() {
        val muffler = product(
            spuCode = "PL001-BLK",
            axis = VariantAxis.PACK_QUANTITY,
            variants = listOf(variant("PL001-BLK-06", "6", packQuantity = 6)),
        )
        val sku = muffler.variants.single()
        val book = listOf(TierPrice(sku.sku, gold, Money.of("93.60")))

        val resolved = PricingPolicy.resolve(muffler, sku, gold, book)
        assertEquals(Money.of("93.60"), resolved.forOneSku)
        assertEquals(Money.of("15.60"), resolved.perUnit)
    }

    @Test
    fun `falls back to list price times pack quantity when a SKU is unpriced`() {
        val muffler = product(
            spuCode = "PL001-BLK",
            basePrice = "19.00",
            axis = VariantAxis.PACK_QUANTITY,
            variants = listOf(variant("PL001-BLK-06", "6", packQuantity = 6)),
        )
        val sku = muffler.variants.single()

        val resolved = PricingPolicy.resolve(muffler, sku, gold, priceBook = emptyList())
        assertEquals(Money.of("114.00"), resolved.forOneSku)
        assertEquals(PriceSource.LIST_FALLBACK, resolved.source)
    }

    @Test
    fun `another SKU's row never applies`() {
        val gloves = product(
            variants = listOf(variant("GL100-BLK-S", "S"), variant("GL100-BLK-M", "M", sortOrder = 1)),
        )
        val small = gloves.variants.first { it.variantValue == "S" }
        val book = listOf(TierPrice(SkuCode("GL100-BLK-M"), gold, Money.of("14.00")))

        assertEquals(PriceSource.LIST_FALLBACK, PricingPolicy.resolve(gloves, small, gold, book).source)
    }

    @Test
    fun `honours a volume break once one exists, without a change to this policy`() {
        // Quantity-based pricing is deferred, but the rule is already correct: inserting
        // a minQty row is all that enabling it requires.
        val gloves = product(variants = listOf(variant("GL100-BLK-M", "M")))
        val sku = gloves.variants.single()
        val book = listOf(
            TierPrice(sku.sku, gold, Money.of("14.00"), Quantity(1)),
            TierPrice(sku.sku, gold, Money.of("13.20"), Quantity(12)),
        )

        assertEquals(Money.of("14.00"), PricingPolicy.resolve(gloves, sku, gold, book, Quantity(1)).forOneSku)
        assertEquals(Money.of("14.00"), PricingPolicy.resolve(gloves, sku, gold, book, Quantity(11)).forOneSku)
        assertEquals(Money.of("13.20"), PricingPolicy.resolve(gloves, sku, gold, book, Quantity(12)).forOneSku)
    }

    @Test
    fun `flags a price that breaches the SKU's advertised floor`() {
        val gloves = product(variants = listOf(variant("GL100-BLK-M", "M", mapPrice = "36.99")))
        val sku = gloves.variants.single()

        assertFalse(PricingPolicy.breachesMap(sku, Money.of("14.00")))
        assertTrue(PricingPolicy.breachesMap(sku, Money.of("40.00")))
    }
}
