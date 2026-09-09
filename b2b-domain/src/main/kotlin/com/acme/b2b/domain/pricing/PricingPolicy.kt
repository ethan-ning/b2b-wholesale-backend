package com.acme.b2b.domain.pricing

import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.types.Money
import com.acme.b2b.types.Quantity
import com.acme.b2b.types.TierId

/**
 * Resolves what a dealer pays for one SKU. A domain service: pure business rule with
 * no I/O, so it is unit-testable with no container and callers supply the rows.
 *
 *   1. the SKU's tier row with the highest minQty <= quantity
 *   2. failing that, list price x pack quantity — a SKU nobody has priced yet
 *
 * Step 2 should be rare enough to alert on; see ARCHITECTURE.md.
 */
object PricingPolicy {

    fun resolve(
        product: Product,
        variant: ProductVariant,
        tierId: TierId,
        priceBook: List<TierPrice>,
        quantity: Quantity = Quantity.ONE,
    ): ResolvedPrice {
        val row = priceBook
            .filter { it.sku == variant.sku && it.tierId == tierId && it.appliesTo(quantity) }
            .maxByOrNull { it.minQty.value }

        return if (row != null) {
            ResolvedPrice(row.price, variant.perUnit(row.price), PriceSource.TIER_PRICE)
        } else {
            val listPrice = product.baseWholesalePrice * variant.packQuantity.value
            ResolvedPrice(listPrice, variant.perUnit(listPrice), PriceSource.LIST_FALLBACK)
        }
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
    TIER_PRICE,
    /** No tier row existed for this SKU — the dealer is seeing list price. */
    LIST_FALLBACK,
}
