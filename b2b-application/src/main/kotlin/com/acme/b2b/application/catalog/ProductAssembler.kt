package com.acme.b2b.application.catalog

import com.acme.b2b.application.catalog.dto.*
import com.acme.b2b.domain.catalog.Category
import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.domain.pricing.ResolvedPrice

/**
 * Entity -> DTO. Lives in the application layer, per the layering: the domain must not
 * know the published contract, and the web layer must not reach into entities.
 *
 * Hand-written rather than MapStruct-generated because the mapping is not field-for-field
 * — prices arrive already resolved for the requesting dealer's tier.
 */
object ProductAssembler {

    /**
     * [variants] is which SKUs to render: the admin sees them all, a dealer sees only
     * what is still on sale. Passed in rather than decided here, because the caller is
     * the one that knows whose view this is.
     */
    fun toDTO(
        product: Product,
        prices: Map<String, ResolvedPrice>,
        categoryNames: Map<Long, String>,
        sellable: Boolean? = null,
        variants: List<ProductVariant> = product.variants,
    ): ProductDTO =
        ProductDTO(
            id = product.id,
            spuCode = product.spuCode.value,
            name = product.name,
            brand = product.brand,
            description = product.description,
            baseWholesalePrice = product.baseWholesalePrice.amount,
            locationCode = product.locationCode,
            variantAxis = product.variantAxis?.label,
            attributes = product.attributes,
            visibility = product.visibility.name,
            sellable = sellable,
            categories = product.categoryIds.map { id ->
                ProductCategoryDTO(
                    id = id,
                    name = categoryNames[id] ?: "",
                    isPrimary = id == product.primaryCategoryId,
                )
            },
            images = product.imageUrls.mapIndexed { index, url ->
                ProductImageDTO(id = null, url = url, altText = product.name, sortOrder = index)
            },
            variants = variants.map { variant ->
                toDTO(variant, prices.getValue(variant.sku.value))
            },
        )

    private fun toDTO(variant: ProductVariant, price: ResolvedPrice): VariantDTO =
        VariantDTO(
            id = variant.id,
            sku = variant.sku.value,
            variantValue = variant.variantValue,
            packQuantity = variant.packQuantity.value,
            upc = variant.upc,
            weight = variant.weight,
            status = if (variant.active) "ACTIVE" else "DISCONTINUED",
            tierPrice = price.forOneSku.amount,
            unitPrice = price.perUnit.amount,
            mapPrice = variant.mapPrice?.amount,
            inventory = InventoryDTO(
                availableStock = variant.stock.available,
                incomingStock = variant.stock.incoming,
                lowStock = variant.stock.isLow,
                outOfStock = variant.stock.isOutOfStock,
                updatedAt = variant.stock.lastSyncedAt.toString(),
            ),
        )

    fun toDTO(category: Category): CategoryDTO = CategoryDTO(
        id = category.id,
        name = category.name,
        slug = category.slug,
        parentId = category.parentId,
        children = category.children.map { toDTO(it) },
    )
}
