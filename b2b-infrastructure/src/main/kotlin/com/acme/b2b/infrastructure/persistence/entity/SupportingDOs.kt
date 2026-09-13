package com.acme.b2b.infrastructure.persistence.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/** The library. A row here outlives every product that happens to show it. */
@Entity
@Table(name = "image")
class ImageDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, columnDefinition = "text")
    var url: String = "",

    @Column(name = "object_key")
    var objectKey: String? = null,

    @Column(nullable = false)
    var filename: String = "",

    @Column(name = "content_type")
    var contentType: String? = null,

    var bytes: Long? = null,
    var width: Int? = null,
    var height: Int? = null,

    @Column(name = "alt_text")
    var altText: String? = null,

    @Column(name = "uploaded_at")
    var uploadedAt: Instant? = null,
)

/** Which products show which images, and in what order. */
@Entity
@Table(name = "product_image")
class ProductImageDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: ProductDO? = null,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "image_id", nullable = false)
    var image: ImageDO? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)

@Entity
@Table(name = "product_category")
class ProductCategoryDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: ProductDO? = null,

    @Column(name = "category_id", nullable = false)
    var categoryId: Long = 0,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,
)

@Entity
@Table(name = "category")
class CategoryDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false, unique = true)
    var slug: String = "",

    @Column(name = "parent_id")
    var parentId: Long? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)

/**
 * One row per SKU per tier. `min_qty` is present and pinned to 1 — quantity-based
 * pricing is deferred, and keeping the column makes enabling it an insert rather than
 * a migration that widens the unique key.
 */
@Entity
@Table(
    name = "tier_price",
    uniqueConstraints = [UniqueConstraint(columnNames = ["sku", "tier_id", "min_qty"])],
)
class TierPriceDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 80)
    var sku: String = "",

    @Column(name = "tier_id", nullable = false)
    var tierId: Long = 0,

    @Column(nullable = false, precision = 10, scale = 2)
    var price: BigDecimal = BigDecimal.ZERO,

    @Column(name = "min_qty", nullable = false)
    var minQty: Int = 1,
)

@Entity
@Table(name = "customer_tier")
class CustomerTierDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    /** So much off the anchor tier's price, on everything this tier buys. */
    @Column(name = "discount_percent", nullable = false)
    var discountPercent: BigDecimal = BigDecimal.ZERO,

    /** The tier whose price the others are worked out from. True for exactly one. */
    @Column(name = "is_anchor", nullable = false)
    var isAnchor: Boolean = false,
)
