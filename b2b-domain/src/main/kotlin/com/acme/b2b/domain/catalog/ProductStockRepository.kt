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

/**
 * Moves SKUs between products when the grouping changes.
 *
 * Kept off [ProductRepository] because it is not an aggregate operation: a regroup moves
 * a variant row from one product to another, which no single aggregate can express — the
 * losing product would have to be loaded and saved just to lose a row it no longer owns.
 *
 * Tier prices key on the SKU rather than the product, so they follow a variant across a
 * regroup untouched. That is what makes moving them safe.
 */
interface ProductGroupingRepository {
    /**
     * Files each SKU under the SPU it now belongs to, creating products that do not yet
     * exist and deleting Sellfox-sourced ones left holding nothing.
     *
     * Deleting an emptied product is not the delete the portal forbids: its SKUs — and
     * their pricing — have moved to the product that now owns them, so what is removed is
     * a shell that a Product could not legally be anyway, having no variants.
     */
    fun regroup(families: List<RegroupedFamily>): RegroupOutcome
}

data class RegroupedFamily(
    val spuCode: String,
    val name: String,
    val axisLabel: String?,
    val members: List<RegroupedSku>,
)

data class RegroupedSku(
    val sku: String,
    val variantValue: String?,
    val packQuantity: Int,
    val sortOrder: Int,
)

data class RegroupOutcome(
    val productsCreated: Int,
    val skusMoved: Int,
    val emptyProductsRemoved: Int,
) {
    val changed: Boolean get() = productsCreated > 0 || skusMoved > 0 || emptyProductsRemoved > 0
}
