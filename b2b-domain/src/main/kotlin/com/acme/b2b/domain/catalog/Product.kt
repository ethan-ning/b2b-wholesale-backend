package com.acme.b2b.domain.catalog

import com.acme.b2b.types.Money
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.VariantAxis

/**
 * Aggregate root for the catalog. A Product is a style/colour; its SKUs vary along a
 * single [variantAxis] — size for apparel, pack quantity for parts.
 *
 * Invariants enforced here, so no caller can assemble a nonsensical product:
 *  - a multi-SKU product declares an axis
 *  - SKU codes are unique within the product
 *
 * There is deliberately no rule that a SKU code start with the SPU code. It held while
 * the catalog was hand-made, and the ERP's does not obey it: Sellfox files
 * "AX-K210-ZN-4 S" under the SPU "AX-K210-ZN S", where the pack count sits before the
 * suffix rather than after the whole code. Since the ERP owns grouping, requiring its
 * codes to nest lexically would reject the grouping it declared — enforcing our
 * convention against the source of truth.
 */
class Product(
    val id: Long?,
    val spuCode: SpuCode,
    val name: String,
    val brand: String?,
    val description: String?,
    /** List price. Only reached when a SKU has no tier price at all — see PricingPolicy. */
    val baseWholesalePrice: Money,
    val locationCode: String?,
    val variantAxis: VariantAxis?,
    val attributes: Map<String, String>,
    val status: ProductStatus,
    val categoryIds: List<Long>,
    val primaryCategoryId: Long?,
    val imageUrls: List<String>,
    variants: List<ProductVariant>,
) {
    val variants: List<ProductVariant> = variants.sortedBy { it.sortOrder }

    init {
        require(name.isNotBlank()) { "Product name must not be blank" }
        require(variants.isNotEmpty()) { "Product $spuCode has no SKUs" }

        val duplicates = variants.groupBy { it.sku }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate SKUs in $spuCode: $duplicates" }

        require(variants.size == 1 || variantAxis != null) {
            "Product $spuCode has ${variants.size} SKUs and must declare a variant axis"
        }
    }

    val isPublished: Boolean get() = status == ProductStatus.ACTIVE

    /**
     * Returns the same product in a different visibility state. Everything else is carried
     * across, so deactivating cannot quietly lose pricing or categories along the way.
     */
    fun withStatus(status: ProductStatus) = Product(
        id, spuCode, name, brand, description, baseWholesalePrice, locationCode, variantAxis,
        attributes, status, categoryIds, primaryCategoryId, imageUrls, variants,
    )

    /**
     * Unfiles this product from a category being deleted. If that category was the primary
     * one, another of its categories takes over: a product with categories but no primary
     * is filed everywhere and placed nowhere.
     */
    fun withoutCategory(categoryId: Long): Product {
        val remaining = categoryIds.filterNot { it == categoryId }
        return Product(
            id, spuCode, name, brand, description, baseWholesalePrice, locationCode, variantAxis,
            attributes, status, remaining,
            if (primaryCategoryId == categoryId) remaining.firstOrNull() else primaryCategoryId,
            imageUrls, variants,
        )
    }

    fun variant(sku: SkuCode): ProductVariant? = variants.firstOrNull { it.sku == sku }

    fun requireVariant(sku: SkuCode): ProductVariant =
        variant(sku) ?: throw NoSuchElementException("$sku is not a SKU of $spuCode")

    val totalAvailableStock: Int get() = variants.sumOf { it.stock.available }
    val hasStock: Boolean get() = totalAvailableStock > 0

    override fun toString() = "Product($spuCode)"
}
