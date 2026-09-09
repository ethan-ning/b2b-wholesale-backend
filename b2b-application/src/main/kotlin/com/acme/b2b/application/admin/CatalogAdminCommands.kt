package com.acme.b2b.application.admin

import java.math.BigDecimal

/**
 * Portal-owned product fields only. Name, brand, description, SPU code and variant axis
 * are absent because the ERP owns them and a sync would overwrite anything set here —
 * an edit that silently reverts is worse than no edit at all.
 */
data class UpdateProductCommand(
    val baseWholesalePrice: BigDecimal,
    val locationCode: String?,
    val status: String,
    val attributes: Map<String, String> = emptyMap(),
    val imageUrls: List<String> = emptyList(),
    val categoryIds: List<Long> = emptyList(),
    val primaryCategoryId: Long? = null,
    /** MAP is ours, so it is the one variant field an admin may set. */
    val variantMapPrices: Map<Long, BigDecimal?> = emptyMap(),
    val tierPrices: List<TierPriceEntry> = emptyList(),
)

data class TierPriceEntry(
    val sku: String,
    val tierId: Long,
    val price: BigDecimal,
    val minQty: Int = 1,
)

data class AdminProductQuery(
    val search: String? = null,
    val status: String? = null,
    /** spuCode (default), name, brand or price. */
    val sort: String? = null,
    val direction: String? = null,
    val page: Int = 0,
    val size: Int = 10,
)

data class CreateCategoryCommand(val name: String, val parentId: Long? = null)

data class RenameCategoryCommand(val name: String)

data class StockQuery(
    val search: String? = null,
    val lowStockOnly: Boolean = false,
    val page: Int = 0,
    val size: Int = 20,
)
