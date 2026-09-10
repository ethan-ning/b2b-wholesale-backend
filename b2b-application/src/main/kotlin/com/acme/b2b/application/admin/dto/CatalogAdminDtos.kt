package com.acme.b2b.application.admin.dto

import com.acme.b2b.application.catalog.dto.ProductDTO
import java.math.BigDecimal

/**
 * A product as the admin sees it: the dealer view, plus the two things kept beside it
 * rather than on it — what each SKU costs a dealer, and where its stock actually sits.
 */
data class AdminProductDTO(
    val product: ProductDTO,
    val tierPrices: List<TierPriceDTO>,
    val stockByWarehouse: List<WarehouseStockDTO>,
)

/**
 * One warehouse's share of one SKU's stock. The variant's own availableStock is these
 * summed, so a total that looks wrong can be traced to the warehouse it came from.
 */
data class WarehouseStockDTO(
    val sku: String,
    val warehouseId: Long,
    val warehouseName: String,
    val available: Int,
    /** In transit to this warehouse — Sellfox's 在途. */
    val incoming: Int,
    val syncedAt: String,
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
    /** 1 for a root. Drives the level styling, and the depth limit. */
    val depth: Int,
    /** Products filed directly under this node, not counting its sub-categories. */
    val productCount: Long,
    /** Distinct products across this node and everything beneath it. */
    val totalProductCount: Long,
    /** False at the deepest allowed level, so the admin sees the ceiling before hitting it. */
    val canAddChild: Boolean,
    /**
     * Whether this node can be deleted, and why not. The same rules the API enforces, so
     * the admin sees the outcome before pressing the button rather than after a 409.
     * Products no longer block: deleting unfiles them.
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
