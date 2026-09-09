package com.acme.b2b.domain.pricing

import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.TierId

/**
 * Port. Loaded in bulk for a set of SKUs so pricing a page of results is one query,
 * not one per SKU.
 */
interface TierPriceRepository {
    fun findFor(skus: Collection<SkuCode>, tierId: TierId): List<TierPrice>
    fun findAllFor(skus: Collection<SkuCode>): List<TierPrice>
    fun replaceFor(sku: SkuCode, rows: List<TierPrice>)
}
