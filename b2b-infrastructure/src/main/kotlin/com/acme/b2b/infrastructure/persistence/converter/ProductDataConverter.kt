package com.acme.b2b.infrastructure.persistence.converter

import com.acme.b2b.domain.catalog.*
import com.acme.b2b.infrastructure.persistence.entity.*
import com.acme.b2b.types.*
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * DO <-> Entity. The one place the two models meet, so the table shape and the domain
 * model can move independently.
 *
 * Note it constructs a Product last, after its variants: the aggregate validates itself
 * on construction, so a malformed row fails here rather than surfacing downstream.
 */
@Component
class ProductDataConverter {

    fun toDomain(row: ProductDO): Product = Product(
        id = row.id,
        spuCode = SpuCode(row.spuCode),
        name = row.name,
        brand = row.brand,
        description = row.description,
        baseWholesalePrice = Money.of(row.baseWholesalePrice),
        locationCode = row.locationCode,
        variantAxis = row.variantAxis?.let { VariantAxis.fromLabel(it) },
        attributes = AttributeCodec.decode(row.attributesJson),
        visibility = ProductVisibility.valueOf(row.visibility),
        categoryIds = row.categories.map { it.categoryId },
        primaryCategoryId = row.categories.firstOrNull { it.isPrimary }?.categoryId,
        imageUrls = row.images.sortedBy { it.sortOrder }.map { it.url },
        variants = row.variants.map { toDomain(it) },
    )

    private fun toDomain(row: ProductVariantDO): ProductVariant = ProductVariant(
        id = row.id,
        sku = SkuCode(row.sku),
        variantValue = row.variantValue,
        packQuantity = PackQuantity(row.packQuantity),
        mapPrice = row.mapPrice?.let { Money.of(it) },
        upc = row.upc,
        weight = row.weight,
        sortOrder = row.sortOrder,
        active = row.status == "ACTIVE",
        stock = StockLevel(
            available = row.availableStock,
            incoming = row.incomingStock,
            lastSyncedAt = row.stockSyncedAt ?: Instant.EPOCH,
        ),
    )

    /**
     * Writes the portal-owned columns onto an existing row. Stock is deliberately not
     * written: the ERP owns it, and letting a product save touch it would silently undo
     * a sync.
     */
    fun applyTo(row: ProductDO, product: Product): ProductDO {
        row.spuCode = product.spuCode.value
        row.name = product.name
        row.brand = product.brand
        row.description = product.description
        row.baseWholesalePrice = product.baseWholesalePrice.amount
        row.locationCode = product.locationCode
        row.variantAxis = product.variantAxis?.label
        row.attributesJson = AttributeCodec.encode(product.attributes)
        row.visibility = product.visibility.name
        row.updatedAt = Instant.now()

        product.variants.forEach { variant ->
            row.variants.firstOrNull { it.sku == variant.sku.value }?.let { variantRow ->
                variantRow.mapPrice = variant.mapPrice?.amount
                variantRow.sortOrder = variant.sortOrder
            }
        }
        return row
    }



    fun toDomain(row: CategoryDO, children: List<Category>): Category = Category(
        id = row.id,
        name = row.name,
        slug = row.slug,
        parentId = row.parentId,
        sortOrder = row.sortOrder,
        children = children,
    )
}

/**
 * Minimal JSON for the display-attribute bag. Deliberately not a Jackson dependency —
 * the values are flat strings, and the column is Postgres JSONB in production where the
 * driver handles it. Replace with the real codec when the column type is finalised.
 */
internal object AttributeCodec {
    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return json.trim().removeSurrounding("{", "}")
            .split(",")
            .filter { it.contains(":") }
            .associate { pair ->
                val (k, v) = pair.split(":", limit = 2)
                k.trim().trim('"') to v.trim().trim('"')
            }
    }

    fun encode(attributes: Map<String, String>): String =
        attributes.entries.joinToString(",", "{", "}") { """"${it.key}":"${it.value}"""" }
}
