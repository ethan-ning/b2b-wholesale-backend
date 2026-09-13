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
/**
 * What one tier pays for one SKU, and where that figure came from.
 *
 * A row exists for every SKU against every tier, whether or not anyone has set a price —
 * because since tiers carry a discount, every combination has an answer. The admin screen
 * needs that answer to show it, and needs [customised] to say whether it is the tier's
 * standing rate or a departure from it.
 */
data class TierPriceDTO(
    val sku: String,
    val tierId: Long,
    val tierName: String,
    /** What this tier actually pays. */
    val price: BigDecimal,
    /** The tier's discount applied to list — what an override is departing from. */
    val standardPrice: BigDecimal,
    val discountPercent: BigDecimal,
    /** True when someone set this price for this SKU, rather than taking the tier's rate. */
    val customised: Boolean,
    /**
     * True when this price is at or above the SKU's advertised floor, which leaves the
     * dealer no margin. A data-entry error rather than a rule the portal enforces.
     */
    val breachesMap: Boolean,
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
