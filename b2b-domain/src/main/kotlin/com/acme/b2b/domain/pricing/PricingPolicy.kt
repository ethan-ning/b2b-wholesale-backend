package com.acme.b2b.domain.pricing

import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.types.Money
import com.acme.b2b.types.Quantity
import com.acme.b2b.domain.customer.CustomerTier

/**
 * Resolves what a dealer pays for one SKU. A domain service: pure business rule with
 * no I/O, so it is unit-testable with no container and callers supply the rows.
 *
 *   1. the SKU's tier row with the highest minQty <= quantity — a price someone set
 *      deliberately for this SKU and this tier
 *   2. failing that, list price x pack quantity, less the tier's standing discount
 *
 * There is no third step. Every tier carries a discount, even if it is nothing, so
 * every SKU has a price the moment it is imported — which is why nothing here has to
 * treat "unpriced" as a state.
 */
object PricingPolicy {

    fun resolve(
        product: Product,
        variant: ProductVariant,
        tier: CustomerTier,
        priceBook: List<TierPrice>,
        quantity: Quantity = Quantity.ONE,
    ): ResolvedPrice {
        val row = priceBook
            .filter { it.sku == variant.sku && it.tierId == tier.id && it.appliesTo(quantity) }
            .maxByOrNull { it.minQty.value }

        if (row != null) {
            return ResolvedPrice(row.price, variant.perUnit(row.price), PriceSource.TIER_PRICE)
        }
        val standard = standardPrice(product, variant, tier)
        return ResolvedPrice(standard, variant.perUnit(standard), PriceSource.TIER_DISCOUNT)
    }

    /**
     * What the tier pays without anyone having said otherwise. Separate because the admin
     * screens show it alongside an override, to say what the override is departing from.
     *
     * The discount comes off the price of the whole SKU, so a pack is discounted as a
     * pack — taking it off the unit price first and multiplying back would round once per
     * unit and drift.
     */
    fun standardPrice(product: Product, variant: ProductVariant, tier: CustomerTier): Money =
        (product.baseWholesalePrice * variant.packQuantity.value).lessDiscount(tier.discount)

    /**
     * List price, for a screen with no dealer in it.
     *
     * The admin catalogue shows what a SKU lists at, not what anybody pays. It asks for
     * that directly rather than resolving against an invented tier — a made-up tier id
     * has to be a real one to satisfy [TierId], so it would have to belong to some dealer
     * and would quietly become their price if the resolution ever changed.
     */
    fun listPrice(product: Product, variant: ProductVariant): ResolvedPrice {
        val price = product.baseWholesalePrice * variant.packQuantity.value
        return ResolvedPrice(price, variant.perUnit(price), PriceSource.LIST)
    }

    /** True when [price] would breach the SKU's advertised floor — a data-entry error. */
    fun breachesMap(variant: ProductVariant, price: Money): Boolean =
        !variant.mapCoversPrice(price)
}

/**
 * [forOneSku] is the price of one SKU — a garment, or a whole 6-pack. [perUnit] divides
 * it by pack quantity so a pack stays comparable with a single.
 */
data class ResolvedPrice(
    val forOneSku: Money,
    val perUnit: Money,
    val source: PriceSource,
)

enum class PriceSource {
    /** Someone set this price for this SKU and this tier. */
    TIER_PRICE,
    /** Nobody did, so the tier's standing discount came off list. */
    TIER_DISCOUNT,
    /** Not a dealer's price at all — what the SKU lists at, for the back office. */
    LIST,
}
