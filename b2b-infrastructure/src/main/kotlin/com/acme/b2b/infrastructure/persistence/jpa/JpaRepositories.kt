package com.acme.b2b.infrastructure.persistence.jpa

import com.acme.b2b.infrastructure.persistence.entity.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Spring Data interfaces — the DAO layer. Not exposed beyond infrastructure: the
 * application talks to the domain's ProductRepository port, whose implementation
 * wraps these.
 */
interface ProductJpaRepository : JpaRepository<ProductDO, Long> {
    fun findBySpuCode(spuCode: String): ProductDO?

    @Query(
        """
        SELECT DISTINCT p FROM ProductDO p
        WHERE (:status IS NULL OR p.status = :status)
          AND (:text IS NULL
               OR LOWER(p.name) LIKE :text
               OR LOWER(p.spuCode) LIKE :text
               OR LOWER(COALESCE(p.brand, '')) LIKE :text
               OR EXISTS (SELECT 1 FROM ProductVariantDO v WHERE v.product = p AND LOWER(v.sku) LIKE :text))
        """
    )
    fun search(
        @Param("text") text: String?,
        @Param("status") status: String?,
    ): List<ProductDO>
}

interface TierPriceJpaRepository : JpaRepository<TierPriceDO, Long> {
    fun findBySkuInAndTierId(skus: Collection<String>, tierId: Long): List<TierPriceDO>
    fun findBySkuIn(skus: Collection<String>): List<TierPriceDO>
    fun deleteBySku(sku: String)
}

interface CategoryJpaRepository : JpaRepository<CategoryDO, Long>

interface ProductCategoryJpaRepository : JpaRepository<ProductCategoryDO, Long> {
    fun findByCategoryIdIn(categoryIds: Collection<Long>): List<ProductCategoryDO>
}

interface CustomerTierJpaRepository : JpaRepository<CustomerTierDO, Long>
