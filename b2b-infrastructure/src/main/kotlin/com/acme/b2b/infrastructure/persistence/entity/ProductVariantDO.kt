package com.acme.b2b.infrastructure.persistence.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "product_variant")
class ProductVariantDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: ProductDO? = null,

    @Column(nullable = false, unique = true, length = 80)
    var sku: String = "",

    @Column(name = "variant_value")
    var variantValue: String? = null,

    /** Sizes are not lexically ordered, so display order is stored, not derived. */
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "pack_quantity", nullable = false)
    var packQuantity: Int = 1,

    /** Advertised price for one of this SKU. Never inherited from the product. */
    @Column(name = "map_price", precision = 10, scale = 2)
    var mapPrice: BigDecimal? = null,

    @Column(length = 14)
    var upc: String? = null,

    @Column(precision = 8, scale = 3)
    var weight: BigDecimal? = null,

    @Column(nullable = false)
    var status: String = "ACTIVE",

    @Column(name = "available_stock", nullable = false)
    var availableStock: Int = 0,

    @Column(name = "incoming_stock", nullable = false)
    var incomingStock: Int = 0,

    @Column(name = "stock_synced_at")
    var stockSyncedAt: Instant? = null,
)
