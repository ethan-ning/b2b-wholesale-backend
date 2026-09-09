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
    val status: String,
    /**
     * False when some SKU has no tier price. Such a product cannot be activated: it would
     * be offered at its base price, which for an ERP import is zero. Null on the
     * dealer-facing responses, where an unsellable product is simply not returned.
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
