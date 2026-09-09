package com.acme.b2b.infrastructure.persistence.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * Data Object: a row of `product`, nothing more. It carries no behaviour and no
 * invariants — those belong to the domain Entity, which this is converted to.
 *
 * Keeping the two apart is the point of the split: the table can be denormalised or
 * indexed without touching a business rule.
 */
@Entity
@Table(name = "product")
class ProductDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "spu_code", nullable = false, unique = true, length = 64)
    var spuCode: String = "",

    @Column(nullable = false)
    var name: String = "",

    var brand: String? = null,

    @Column(columnDefinition = "text")
    var description: String? = null,

    @Column(name = "base_wholesale_price", nullable = false, precision = 10, scale = 2)
    var baseWholesalePrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "location_code")
    var locationCode: String? = null,

    @Column(name = "variant_axis")
    var variantAxis: String? = null,

    /** Display-only key/value bag. Seeded from the ERP at import, ours afterwards. */
    @Column(name = "attributes_json", columnDefinition = "text")
    var attributesJson: String? = null,

    @Column(nullable = false)
    var status: String = "ACTIVE",

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @OneToMany(mappedBy = "product", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var variants: MutableList<ProductVariantDO> = mutableListOf(),

    @OneToMany(mappedBy = "product", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var images: MutableList<ProductImageDO> = mutableListOf(),

    @OneToMany(mappedBy = "product", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var categories: MutableList<ProductCategoryDO> = mutableListOf(),
)
