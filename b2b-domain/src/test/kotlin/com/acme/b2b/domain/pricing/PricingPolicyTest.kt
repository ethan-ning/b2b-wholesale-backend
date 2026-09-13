package com.acme.b2b.domain.pricing

import com.acme.b2b.domain.ProductFixtures.product
import com.acme.b2b.domain.ProductFixtures.variant
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.types.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricingPolicyTest {

    // Three tiers as the portal has them: one paying list, two with a standing discount.
    private val standard = CustomerTier(TierId(3), "Default", 1, DiscountPercent.NONE)
    private val silverTier = CustomerTier(TierId(2), "Silver", 2, DiscountPercent.of(7))
    private val goldTier = CustomerTier(TierId(1), "Gold", 3, DiscountPercent.of(18))
    private val gold = goldTier.id
    private val silver = silverTier.id

    @Test
    fun `uses the SKU's tier row`() {
        val gloves = product(variants = listOf(variant("GL100-BLK-M", "M")))
        val sku = gloves.variants.single()
        val book = listOf(
            TierPrice(sku.sku, gold, Money.of("14.00")),
            TierPrice(sku.sku, silver, Money.of("16.50")),
        )

        assertEquals(Money.of("14.00"), PricingPolicy.resolve(gloves, sku, goldTier, book).forOneSku)
        assertEquals(Money.of("16.50"), PricingPolicy.resolve(gloves, sku, silverTier, book).forOneSku)
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

        val resolved = PricingPolicy.resolve(muffler, sku, goldTier, book)
        assertEquals(Money.of("93.60"), resolved.forOneSku)
        assertEquals(Money.of("15.60"), resolved.perUnit)
    }

    @Test
    fun `with no row for the SKU, the tier's standing discount comes off list`() {
        val muffler = product(
            spuCode = "PL001-BLK",
            basePrice = "19.00",
            axis = VariantAxis.PACK_QUANTITY,
            variants = listOf(variant("PL001-BLK-06", "6", packQuantity = 6)),
        )
        val sku = muffler.variants.single()

        // A six-pack at 19.00 a unit is 114.00 at list; Gold takes 18% off the pack.
        val resolved = PricingPolicy.resolve(muffler, sku, goldTier, priceBook = emptyList())
        assertEquals(Money.of("93.48"), resolved.forOneSku)
        assertEquals(Money.of("15.58"), resolved.perUnit)
        assertEquals(PriceSource.TIER_DISCOUNT, resolved.source)
    }

    @Test
    fun `a tier with no discount pays list`() {
        val muffler = product(basePrice = "19.00", variants = listOf(variant("PL001-BLK-01", "1")))
        val sku = muffler.variants.single()

        val resolved = PricingPolicy.resolve(muffler, sku, standard, priceBook = emptyList())
        assertEquals(Money.of("19.00"), resolved.forOneSku)
        assertEquals(PriceSource.TIER_DISCOUNT, resolved.source)
    }

    /**
     * The discount comes off the price of the whole SKU. Discounting the unit and
     * multiplying back rounds once per unit, and a twelve-pack drifts by cents.
     */
    @Test
    fun `a pack is discounted as a pack, not a unit at a time`() {
        val muffler = product(
            basePrice = "9.99",
            axis = VariantAxis.PACK_QUANTITY,
            variants = listOf(variant("PL001-BLK-12", "12", packQuantity = 12)),
        )
        val sku = muffler.variants.single()

        // 119.88 less 7% is 111.4884 -> 111.49. Per unit first would give 9.29 x 12 = 111.48.
        assertEquals(Money.of("111.49"),
            PricingPolicy.resolve(muffler, sku, silverTier, emptyList()).forOneSku)
    }

    @Test
    fun `an override wins over the tier's discount`() {
        val gloves = product(basePrice = "18.00", variants = listOf(variant("GL100-BLK-M", "M")))
        val sku = gloves.variants.single()
        val book = listOf(TierPrice(sku.sku, gold, Money.of("12.00")))

        val resolved = PricingPolicy.resolve(gloves, sku, goldTier, book)
        assertEquals(Money.of("12.00"), resolved.forOneSku)
        assertEquals(PriceSource.TIER_PRICE, resolved.source)
        // And the standing price is still knowable, so a screen can show what it departed from.
        assertEquals(Money.of("14.76"), PricingPolicy.standardPrice(gloves, sku, goldTier))
    }

    @Test
    fun `another SKU's row never applies`() {
        val gloves = product(
            variants = listOf(variant("GL100-BLK-S", "S"), variant("GL100-BLK-M", "M", sortOrder = 1)),
        )
        val small = gloves.variants.first { it.variantValue == "S" }
        val book = listOf(TierPrice(SkuCode("GL100-BLK-M"), gold, Money.of("14.00")))

        assertEquals(PriceSource.TIER_DISCOUNT, PricingPolicy.resolve(gloves, small, goldTier, book).source)
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

        assertEquals(Money.of("14.00"), PricingPolicy.resolve(gloves, sku, goldTier, book, Quantity(1)).forOneSku)
        assertEquals(Money.of("14.00"), PricingPolicy.resolve(gloves, sku, goldTier, book, Quantity(11)).forOneSku)
        assertEquals(Money.of("13.20"), PricingPolicy.resolve(gloves, sku, goldTier, book, Quantity(12)).forOneSku)
    }

    @Test
    fun `flags a price that breaches the SKU's advertised floor`() {
        val gloves = product(variants = listOf(variant("GL100-BLK-M", "M", mapPrice = "36.99")))
        val sku = gloves.variants.single()

        assertFalse(PricingPolicy.breachesMap(sku, Money.of("14.00")))
        assertTrue(PricingPolicy.breachesMap(sku, Money.of("40.00")))
    }
}
