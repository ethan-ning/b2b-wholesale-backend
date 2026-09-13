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
     * It used to mean "every SKU has a tier price". Tiers carry a standing discount now,
     * so a price exists from the moment of import and no SKU can be unpriced — but the
     * hazard behind that rule did not go away, it moved. A discount off nothing is still
     * nothing, so an import that arrives without a list price would be offered free.
     *
     * That is the whole rule: something to sell, at a price above zero.
     */
    fun isSellable(): Boolean = unsellableReason() == null

    /**
     * Why this product could not be shown, or null if it could.
     *
     * Two separate conditions, reported separately. Said in one sentence — "no SKU on
     * sale with a list price" — a reader cannot tell which of them failed, and the half
     * that did not sends them looking for a setting that does not exist.
     */
    fun unsellableReason(): UnsellableReason? = when {
        onSaleVariants.isEmpty() -> UnsellableReason.NOTHING_ON_SALE
        baseWholesalePrice.isZero() -> UnsellableReason.NO_LIST_PRICE
        else -> null
    }

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

/** Why a product cannot be shown to dealers. Each says what to do about it. */
enum class UnsellableReason {
    /** Every SKU is discontinued, so there is nothing to sell — not a pricing problem. */
    NOTHING_ON_SALE,

    /** No list price, and a tier discount off nothing is nothing. */
    NO_LIST_PRICE,
}
