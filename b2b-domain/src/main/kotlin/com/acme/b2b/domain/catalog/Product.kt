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
 * A SKU code deliberately need not start with its SPU code: Sellfox files "AX-K210-ZN-4 S"
 * under "AX-K210-ZN S", and since the ERP owns grouping, requiring its codes to nest
 * lexically would reject the grouping it declared.
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
    val visibility: ProductVisibility,
    val categoryIds: List<Long>,
    val primaryCategoryId: Long?,
    val images: List<ProductImageRef>,
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

    val isVisible: Boolean get() = visibility == ProductVisibility.VISIBLE

    /**
     * The SKUs a dealer may buy — everything the supplier still sells.
     *
     * A discontinued SKU is kept on the product so its pricing survives the supplier
     * dropping it and returning to it, but it is not on offer, and showing one is
     * offering to sell something that cannot be bought. The admin sees all of them; this
     * is what the dealer sees.
     */
    val onSaleVariants: List<ProductVariant> get() = variants.filter { it.active }

    /**
     * Whether this product could be shown to a dealer at all, whatever [visibility] says.
     *
     * An ERP import arrives with no dealer price — Sellfox knows cost and stock, not what
     * a dealer pays — so it is not merely hidden until someone prices it, it is unsellable:
     * making it visible would offer it at [baseWholesalePrice], which for an import is zero.
     *
     * [pricedSkus] is the SKU codes carrying at least one tier price. Every SKU still on
     * sale must be covered — a half-priced product shows some pack sizes at list price and
     * the rest at nothing, which reads as a bug rather than as a missing price.
     */
    fun isSellable(pricedSkus: Set<SkuCode>): Boolean =
        onSaleVariants.isNotEmpty() && onSaleVariants.all { it.sku in pricedSkus }

    /**
     * Returns the same product in a different visibility state. Everything else is carried
     * across, so deactivating cannot quietly lose pricing or categories along the way.
     */
    fun withVisibility(visibility: ProductVisibility) = Product(
        id, spuCode, name, brand, description, baseWholesalePrice, locationCode, variantAxis,
        attributes, visibility, categoryIds, primaryCategoryId, images, variants,
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
            attributes, visibility, remaining,
            if (primaryCategoryId == categoryId) remaining.firstOrNull() else primaryCategoryId,
            images, variants,
        )
    }

    fun variant(sku: SkuCode): ProductVariant? = variants.firstOrNull { it.sku == sku }

    fun requireVariant(sku: SkuCode): ProductVariant =
        variant(sku) ?: throw NoSuchElementException("$sku is not a SKU of $spuCode")

    val totalAvailableStock: Int get() = variants.sumOf { it.stock.available }
    val hasStock: Boolean get() = totalAvailableStock > 0

    override fun toString() = "Product($spuCode)"
}
