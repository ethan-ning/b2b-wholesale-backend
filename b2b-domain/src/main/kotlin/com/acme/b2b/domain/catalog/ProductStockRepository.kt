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
    /** The rows [available] and [incoming] are the sum of, kept for the admin view. */
    val byWarehouse: List<WarehouseStock> = emptyList(),
)

data class WarehouseStock(
    val warehouseId: Long,
    val available: Int,
    val incoming: Int,
)

/**
 * Where a SKU's stock actually sits, for the one screen that asks.
 *
 * A read port of its own rather than a field on Product: the breakdown is wanted on a
 * single admin detail view, and hanging it off the aggregate would load warehouse rows
 * for every product a dealer ever lists.
 */
interface VariantStockBreakdownRepository {
    fun findBySkus(skus: List<String>): List<WarehouseStockLine>
}

data class WarehouseStockLine(
    val sku: String,
    val warehouseId: Long,
    /** Blank when the warehouse has left the scope and is no longer in the registry. */
    val warehouseName: String,
    val available: Int,
    val incoming: Int,
    val syncedAt: Instant,
)
