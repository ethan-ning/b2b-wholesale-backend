package com.acme.b2b.domain.pricing

import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.types.Money
import com.acme.b2b.types.Quantity

/**
 * What a dealer pays for one SKU. A domain service: pure business rule with no I/O, so it
 * is unit-testable with no container and callers supply the rows.
 *
 * One rule, and everything is a case of it:
 *
 *     a price set for this SKU and this tier, or failing that
 *     the SKU's anchor price less this tier's discount
 *
 * The anchor tier has no second case — its price is the one that is stated, not derived —
 * so a SKU with no anchor price has no price at all, and [resolve] says so by returning
 * null rather than inventing one.
 *
 * Supplier cost is not in here. It is what the business pays, not what a dealer does.
 */
object PricingPolicy {

    fun resolve(
        variant: ProductVariant,
        tier: CustomerTier,
        priceBook: List<TierPrice>,
        anchor: CustomerTier,
        quantity: Quantity = Quantity.ONE,
    ): ResolvedPrice? {
        stated(variant, tier, priceBook, quantity)?.let {
            return ResolvedPrice(it, variant.perUnit(it), PriceSource.STATED)
        }
        if (tier.anchor) return null

        val anchorPrice = stated(variant, anchor, priceBook, quantity) ?: return null
        val price = anchorPrice.lessDiscount(tier.discount)
        return ResolvedPrice(price, variant.perUnit(price), PriceSource.DISCOUNTED)
    }

    /**
     * What this tier would pay without a price of its own — what an override departs from.
     * Null for the anchor tier, which has nothing to depart from.
     */
    fun standardPrice(
        variant: ProductVariant,
        tier: CustomerTier,
        priceBook: List<TierPrice>,
        anchor: CustomerTier,
    ): Money? {
        if (tier.anchor) return null
        return stated(variant, anchor, priceBook, Quantity.ONE)?.lessDiscount(tier.discount)
    }

    /** The SKU's anchor price: the figure every other tier is worked out from. */
    fun anchorPrice(variant: ProductVariant, priceBook: List<TierPrice>, anchor: CustomerTier): Money? =
        stated(variant, anchor, priceBook, Quantity.ONE)

    /** True when [price] would breach the SKU's advertised floor — a data-entry error. */
    fun breachesMap(variant: ProductVariant, price: Money): Boolean =
        !variant.mapCoversPrice(price)

    /** The row someone set for this SKU and tier, taking the best applicable volume break. */
    private fun stated(
        variant: ProductVariant,
        tier: CustomerTier,
        priceBook: List<TierPrice>,
        quantity: Quantity,
    ): Money? = priceBook
        .filter { it.sku == variant.sku && it.tierId == tier.id && it.appliesTo(quantity) }
        .maxByOrNull { it.minQty.value }
        ?.price
}

/**
 * [forOneSku] is the price of one SKU — a garment, or a whole 6-pack. [perUnit] divides it
 * by pack quantity so a pack stays comparable with a single.
 */
data class ResolvedPrice(
    val forOneSku: Money,
    val perUnit: Money,
    val source: PriceSource,
)

enum class PriceSource {
    /** Someone set this price for this SKU and this tier. */
    STATED,
    /** Nobody did, so the tier's discount came off the SKU's anchor price. */
    DISCOUNTED,
}
