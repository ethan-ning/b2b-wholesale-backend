package com.acme.b2b.domain.catalog

import java.time.Instant

/**
 * Stock writes, kept off [ProductRepository] deliberately.
 *
 * Loading each product aggregate to change two integers on one of its SKUs would be a
 * misuse of the aggregate and, at a few thousand SKUs every quarter hour, slow enough to
 * matter. Stock is also the one part of a product no portal user may edit, so the write
 * path having no other reach is a property worth keeping.
 */
interface ProductStockRepository {
    /**
     * Applies stock to the SKUs the catalog actually has. Returns how many matched —
     * Sellfox holds far more SKUs than the portal carries, and the difference is normal
     * rather than an error.
     */
    fun applyStock(updates: List<SkuStockUpdate>): Int
}

data class SkuStockUpdate(
    val sku: String,
    val available: Int,
    val incoming: Int,
    val syncedAt: Instant,
)
