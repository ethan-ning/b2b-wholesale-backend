package com.acme.b2b.application.admin.dto

import com.acme.b2b.application.catalog.dto.ProductDTO
import java.math.BigDecimal

/** A product as the admin sees it: the dealer view plus its price book. */
data class AdminProductDTO(
    val product: ProductDTO,
    val tierPrices: List<TierPriceDTO>,
)

/**
 * One tier_price row. minQty is always 1 in the MVP — quantity-based pricing is
 * deferred — but the field is carried so enabling it stays additive.
 */
data class TierPriceDTO(
    val sku: String,
    val tierId: Long,
    val tierName: String,
    val price: BigDecimal,
    val minQty: Int,
)

data class CategoryNodeDTO(
    val id: Long?,
    val name: String,
    val slug: String,
    val parentId: Long?,
    val sortOrder: Int,
    /** Products filed directly under this node, not counting its sub-categories. */
    val productCount: Long,
    /**
     * Whether this node can be deleted, and why not. The same rules the API enforces, so
     * the admin sees the outcome before pressing the button rather than after a 409.
     */
    val deletable: Boolean,
    val blockedReason: String?,
    val children: List<CategoryNodeDTO>,
)

data class SkuStockDTO(
    val variantId: Long,
    val sku: String,
    val spuCode: String,
    val productName: String,
    val variantValue: String?,
    val availableStock: Int,
    val incomingStock: Int,
    val lowStock: Boolean,
    val outOfStock: Boolean,
    val lastSyncedAt: String,
)

data class DashboardStatsDTO(
    val totalProducts: Long,
    val activeProducts: Long,
    val totalCustomers: Long,
    val activeCustomers: Long,
    val lowStockAlerts: Long,
    val outOfStockCount: Long,
)
