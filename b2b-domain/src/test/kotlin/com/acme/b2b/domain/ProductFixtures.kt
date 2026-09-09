package com.acme.b2b.domain

import com.acme.b2b.domain.catalog.*
import com.acme.b2b.types.*
import java.time.Instant

/** Builders so tests read as the case under test, not as object assembly. */
object ProductFixtures {

    fun variant(
        sku: String,
        variantValue: String? = null,
        packQuantity: Int = 1,
        mapPrice: String? = null,
        available: Int = 10,
        sortOrder: Int = 0,
    ) = ProductVariant(
        id = null,
        sku = SkuCode(sku),
        variantValue = variantValue,
        packQuantity = PackQuantity(packQuantity),
        mapPrice = mapPrice?.let { Money.of(it) },
        upc = null,
        weight = null,
        sortOrder = sortOrder,
        active = true,
        stock = StockLevel(available, 0, Instant.EPOCH),
    )

    fun product(
        spuCode: String = "GL100-BLK",
        basePrice: String = "18.00",
        axis: VariantAxis? = VariantAxis.SIZE,
        variants: List<ProductVariant> = listOf(variant("GL100-BLK-M", "M")),
    ) = Product(
        id = 1,
        spuCode = SpuCode(spuCode),
        name = "Riding Gloves",
        brand = "RiderEdge",
        description = null,
        baseWholesalePrice = Money.of(basePrice),
        locationCode = "C2-1",
        variantAxis = axis,
        attributes = emptyMap(),
        status = ProductStatus.ACTIVE,
        categoryIds = listOf(22),
        primaryCategoryId = 22,
        imageUrls = emptyList(),
        variants = variants,
    )
}
