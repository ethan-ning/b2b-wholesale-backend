package com.acme.b2b.domain.inventory

import com.acme.b2b.domain.catalog.StockLevel
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode

/**
 * A read model: one SKU's stock with just enough of its product to be identifiable.
 *
 * Deliberately not the Product aggregate. The admin's stock screen wants a flat list
 * across every SKU in the catalog, and loading whole aggregates to render rows would
 * be both slower and a misuse of the aggregate, which exists to enforce invariants on
 * writes.
 */
data class SkuStock(
    val variantId: Long,
    val sku: SkuCode,
    val spuCode: SpuCode,
    val productName: String,
    val variantValue: String?,
    val stock: StockLevel,
)

data class StockSearchCriteria(
    val text: String? = null,
    /**
     * Narrow to SKUs running low — in stock, but under the threshold. Matches
     * StockLevel.isLow and therefore the dashboard's lowStockAlerts count. Out of stock is
     * a separate state, visible on every row as `outOfStock`.
     */
    val lowStockOnly: Boolean = false,
)

/**
 * Port for the stock read side. Separate from ProductRepository because it answers
 * questions about SKUs across the whole catalog, not about one aggregate.
 */
interface StockQueryPort {
    fun search(criteria: StockSearchCriteria, page: Page): PageOf<SkuStock>
    fun countLowStock(): Long
    fun countOutOfStock(): Long
}
