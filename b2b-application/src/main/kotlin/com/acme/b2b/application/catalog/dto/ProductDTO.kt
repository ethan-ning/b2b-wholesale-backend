package com.acme.b2b.application.catalog.dto

import java.math.BigDecimal

/**
 * What the dealer portal receives. Deliberately not the domain Entity: this shape is a
 * published contract with the frontend and changes on its own schedule.
 *
 * Money is a plain BigDecimal here — Money is a domain type and does not leak outward.
 */
data class ProductDTO(
    val id: Long?,
    val spuCode: String,
    val name: String,
    val brand: String?,
    val description: String?,
    val baseWholesalePrice: BigDecimal,
    val locationCode: String?,
    /** "Size" or "Pack Qty" — titles the variant column. */
    val variantAxis: String?,
    val attributes: Map<String, String>,
    /** VISIBLE or HIDDEN — the portal's only lever. Not the ERP's on-sale state. */
    val visibility: String,
    /**
     * Whether the product could be shown to a dealer at all — see `Product.isSellable`.
     * Null on the dealer-facing responses, where an unsellable product is not returned.
     */
    val sellable: Boolean? = null,
    val categories: List<ProductCategoryDTO>,
    val images: List<ProductImageDTO>,
    val variants: List<VariantDTO>,
)

data class ProductCategoryDTO(val id: Long, val name: String, val isPrimary: Boolean)

data class ProductImageDTO(val id: Long?, val url: String, val altText: String?, val sortOrder: Int)

data class VariantDTO(
    val id: Long?,
    val sku: String,
    val variantValue: String?,
    val packQuantity: Int,
    val upc: String?,
    val weight: BigDecimal?,
    val status: String,
    /** What the dealer pays for one of this SKU — a garment, or a whole pack. */
    val tierPrice: BigDecimal,
    /** tierPrice / packQuantity, so a pack compares against a single. */
    val unitPrice: BigDecimal,
    val mapPrice: BigDecimal?,
    val inventory: InventoryDTO,
)

data class InventoryDTO(
    val availableStock: Int,
    val incomingStock: Int,
    /**
     * In stock but running down, per StockLevel.LOW_STOCK_THRESHOLD. Sent rather than left
     * for the client to work out: a threshold copied into the UI is a second definition,
     * and the two drift into disagreeing about the same row.
     */
    val lowStock: Boolean,
    val outOfStock: Boolean,
    val updatedAt: String,
)

data class PagedDTO<T>(
    val content: List<T>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
)

data class CategoryDTO(
    val id: Long?,
    val name: String,
    val slug: String,
    val parentId: Long?,
    val children: List<CategoryDTO>,
)
