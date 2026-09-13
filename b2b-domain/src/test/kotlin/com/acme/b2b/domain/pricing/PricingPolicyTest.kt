package com.acme.b2b.domain.pricing

import com.acme.b2b.domain.ProductFixtures.product
import com.acme.b2b.domain.ProductFixtures.variant
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.types.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One rule, and every test here is a case of it: a price stated for this SKU and tier, or
 * failing that the SKU's anchor price less this tier's discount.
 */
class PricingPolicyTest {

    private val default = CustomerTier(TierId(3), "Default", 1, DiscountPercent.NONE, anchor = true)
    private val silver = CustomerTier(TierId(2), "Silver", 2, DiscountPercent.of(7))
    private val gold = CustomerTier(TierId(1), "Gold", 3, DiscountPercent.of(18))

    private val gloves = product(variants = listOf(variant("GL100-BLK-M", "M")))
    private val sku = gloves.variants.single()

    /** The SKU's default price, which is the only price anyone has to state. */
    private fun book(vararg rows: TierPrice) = rows.toList()
    private fun defaultPrice(amount: String) = TierPrice(sku.sku, default.id, Money.of(amount))

    @Test
    fun `a tier takes its discount off the SKU's default price`() {
        val prices = book(defaultPrice("18.00"))

        assertEquals(Money.of("18.00"), PricingPolicy.resolve(sku, default, prices, default)!!.forOneSku)
        assertEquals(Money.of("16.74"), PricingPolicy.resolve(sku, silver, prices, default)!!.forOneSku)
        assertEquals(Money.of("14.76"), PricingPolicy.resolve(sku, gold, prices, default)!!.forOneSku)
    }

    @Test
    fun `a price stated for the tier wins over the discount`() {
        val prices = book(defaultPrice("18.00"), TierPrice(sku.sku, gold.id, Money.of("12.00")))

        val resolved = PricingPolicy.resolve(sku, gold, prices, default)!!
        // Twelve, not the 14.76 the discount would have given — so this is the stated one.
        assertEquals(Money.of("12.00"), resolved.forOneSku)
        // And what it departed from is still knowable, for a screen to show beside it.
        assertEquals(Money.of("14.76"), PricingPolicy.standardPrice(sku, gold, prices, default))
    }

    /**
     * The point of the change: cost is the business's number, the default price is the
     * dealer's, and moving one must not move the other.
     */
    @Test
    fun `nothing here reads the product's cost`() {
        val cheap = product(basePrice = "1.00", variants = listOf(variant("GL100-BLK-M", "M")))
        val dear = product(basePrice = "999.00", variants = listOf(variant("GL100-BLK-M", "M")))
        val prices = book(defaultPrice("18.00"))

        assertEquals(
            PricingPolicy.resolve(cheap.variants.single(), gold, prices, default)!!.forOneSku,
            PricingPolicy.resolve(dear.variants.single(), gold, prices, default)!!.forOneSku,
        )
    }

    @Test
    fun `changing the default price moves every tier that has not been given one`() {
        val before = book(defaultPrice("18.00"), TierPrice(sku.sku, gold.id, Money.of("12.00")))
        val after = book(defaultPrice("20.00"), TierPrice(sku.sku, gold.id, Money.of("12.00")))

        // Silver follows.
        assertEquals(Money.of("16.74"), PricingPolicy.resolve(sku, silver, before, default)!!.forOneSku)
        assertEquals(Money.of("18.60"), PricingPolicy.resolve(sku, silver, after, default)!!.forOneSku)
        // Gold was given a price, so it does not.
        assertEquals(Money.of("12.00"), PricingPolicy.resolve(sku, gold, after, default)!!.forOneSku)
    }

    @Test
    fun `a SKU with no default price has no price at any tier`() {
        assertNull(PricingPolicy.resolve(sku, default, emptyList(), default))
        assertNull(PricingPolicy.resolve(sku, silver, emptyList(), default))
        assertNull(PricingPolicy.standardPrice(sku, silver, emptyList(), default))
    }

    /** Even then a tier given its own price has one — it depends on nothing else. */
    @Test
    fun `a stated price stands without a default price`() {
        val prices = book(TierPrice(sku.sku, gold.id, Money.of("12.00")))

        assertEquals(Money.of("12.00"), PricingPolicy.resolve(sku, gold, prices, default)!!.forOneSku)
        assertNull(PricingPolicy.resolve(sku, silver, prices, default))
    }

    @Test
    fun `the anchor tier has nothing to depart from`() {
        assertNull(PricingPolicy.standardPrice(sku, default, book(defaultPrice("18.00")), default))
    }

    @Test
    fun `another SKU's price never applies`() {
        val pair = product(
            variants = listOf(variant("GL100-BLK-S", "S"), variant("GL100-BLK-M", "M", sortOrder = 1)),
        )
        val small = pair.variants.first { it.variantValue == "S" }
        val prices = book(TierPrice(SkuCode("GL100-BLK-M"), default.id, Money.of("18.00")))

        assertNull(PricingPolicy.resolve(small, gold, prices, default))
    }

    @Test
    fun `pack quantity does not multiply the price, but still divides it for comparison`() {
        val muffler = product(
            axis = VariantAxis.PACK_QUANTITY,
            variants = listOf(variant("PL001-BLK-06", "6", packQuantity = 6)),
        )
        val pack = muffler.variants.single()
        val prices = listOf(TierPrice(pack.sku, default.id, Money.of("114.00")))

        val resolved = PricingPolicy.resolve(pack, gold, prices, default)!!
        assertEquals(Money.of("93.48"), resolved.forOneSku)
        assertEquals(Money.of("15.58"), resolved.perUnit)
    }

    @Test
    fun `honours a volume break once one exists, without a change to this policy`() {
        val prices = book(
            defaultPrice("18.00"),
            TierPrice(sku.sku, gold.id, Money.of("14.00"), Quantity(1)),
            TierPrice(sku.sku, gold.id, Money.of("13.20"), Quantity(12)),
        )

        assertEquals(Money.of("14.00"), PricingPolicy.resolve(sku, gold, prices, default, Quantity(11))!!.forOneSku)
        assertEquals(Money.of("13.20"), PricingPolicy.resolve(sku, gold, prices, default, Quantity(12))!!.forOneSku)
    }

    @Test
    fun `flags a price that breaches the SKU's advertised floor`() {
        val capped = product(variants = listOf(variant("GL100-BLK-M", "M", mapPrice = "36.99")))
        val one = capped.variants.single()

        assertFalse(PricingPolicy.breachesMap(one, Money.of("14.00")))
        assertTrue(PricingPolicy.breachesMap(one, Money.of("40.00")))
    }
}
