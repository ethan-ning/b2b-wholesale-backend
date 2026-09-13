package com.acme.b2b.domain.inventory

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf

/**
 * Port for the stock read side. Separate from ProductRepository because it answers
 * questions about SKUs across the whole catalog, not about one aggregate.
 */
interface StockQueryPort {
    fun search(criteria: StockSearchCriteria, page: Page): PageOf<SkuStock>
    fun countLowStock(): Long
    fun countOutOfStock(): Long
}
