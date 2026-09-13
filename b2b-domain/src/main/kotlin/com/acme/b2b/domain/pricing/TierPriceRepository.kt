package com.acme.b2b.domain.pricing

import com.acme.b2b.types.SkuCode

/**
 * Port. Loaded in bulk for a set of SKUs so pricing a page of results is one query,
 * not one per SKU.
 */
interface TierPriceRepository {
    fun findAllFor(skus: Collection<SkuCode>): List<TierPrice>
    fun replaceFor(sku: SkuCode, rows: List<TierPrice>)
}
