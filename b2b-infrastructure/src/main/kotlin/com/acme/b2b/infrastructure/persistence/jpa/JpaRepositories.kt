package com.acme.b2b.infrastructure.persistence.jpa

import com.acme.b2b.infrastructure.persistence.entity.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
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

    fun countByStatus(status: String): Long

    @Query("SELECT DISTINCT p FROM ProductDO p JOIN p.categories c WHERE c.categoryId = :categoryId")
    fun findByCategoryId(@Param("categoryId") categoryId: Long): List<ProductDO>
}

/**
 * The stock read side. Projects straight to a row rather than loading ProductDO graphs —
 * the admin's stock screen is a flat list across every SKU, and hydrating aggregates to
 * render it would be slower and a misuse of the aggregate.
 */
interface ProductVariantJpaRepository : JpaRepository<ProductVariantDO, Long> {

    @Query(
        """
        SELECT v FROM ProductVariantDO v
        JOIN v.product p
        WHERE (:text IS NULL
               OR LOWER(v.sku) LIKE :text
               OR LOWER(p.name) LIKE :text
               OR LOWER(p.spuCode) LIKE :text)
          AND (:lowStockOnly = FALSE
               OR (v.availableStock > 0 AND v.availableStock < :threshold))
        ORDER BY p.spuCode, v.sortOrder
        """
    )
    fun searchStock(
        @Param("text") text: String?,
        @Param("lowStockOnly") lowStockOnly: Boolean,
        @Param("threshold") threshold: Int,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<ProductVariantDO>

    fun countByAvailableStockGreaterThanAndAvailableStockLessThan(floor: Int, ceiling: Int): Long
    fun countByAvailableStock(availableStock: Int): Long
    fun findBySkuIn(skus: Collection<String>): List<ProductVariantDO>
}

interface TierPriceJpaRepository : JpaRepository<TierPriceDO, Long> {
    fun findBySkuInAndTierId(skus: Collection<String>, tierId: Long): List<TierPriceDO>
    fun findBySkuIn(skus: Collection<String>): List<TierPriceDO>
    fun deleteBySku(sku: String)
}

interface CategoryJpaRepository : JpaRepository<CategoryDO, Long> {
    fun existsByParentId(parentId: Long): Boolean
    fun existsBySlug(slug: String): Boolean
}

interface ProductCategoryJpaRepository : JpaRepository<ProductCategoryDO, Long> {
    fun findByCategoryIdIn(categoryIds: Collection<Long>): List<ProductCategoryDO>

    /** (categoryId, productId) pairs. Reads the FK column; no join to product. */
    @Query("SELECT pc.categoryId, pc.product.id FROM ProductCategoryDO pc")
    fun categoryProductPairs(): List<Array<Any>>
}

interface CustomerTierJpaRepository : JpaRepository<CustomerTierDO, Long>

interface AdminUserJpaRepository : JpaRepository<AdminUserDO, Long> {
    fun findByEmail(email: String): AdminUserDO?
}

interface CustomerJpaRepository : JpaRepository<CustomerDO, Long> {
    fun findByEmail(email: String): CustomerDO?
    fun existsByEmail(email: String): Boolean
    fun existsByTierId(tierId: Long): Boolean
    fun countByStatus(status: String): Long

    @Query(
        """
        SELECT c FROM CustomerDO c
        WHERE (:status IS NULL OR c.status = :status)
          AND (:text IS NULL
               OR LOWER(c.name) LIKE :text
               OR LOWER(c.email) LIKE :text
               OR LOWER(c.companyName) LIKE :text)
        ORDER BY c.id
        """
    )
    fun search(
        @Param("text") text: String?,
        @Param("status") status: String?,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<CustomerDO>
}

// ─── Sellfox ─────────────────────────────────────────────────────────────

interface SellfoxCategoryJpaRepository : JpaRepository<SellfoxCategoryDO, String> {
    fun findBySelectedTrue(): List<SellfoxCategoryDO>
}

interface SellfoxWarehouseJpaRepository : JpaRepository<SellfoxWarehouseDO, Long> {
    fun findBySelectedTrue(): List<SellfoxWarehouseDO>
}

interface SellfoxSkuLinkJpaRepository : JpaRepository<SellfoxSkuLinkDO, String> {
    fun findByFullCidIn(fullCids: Collection<String>): List<SellfoxSkuLinkDO>
}

interface SellfoxSyncRunJpaRepository : JpaRepository<SellfoxSyncRunDO, Long> {
    fun findAllByOrderByStartedAtDesc(pageable: Pageable): List<SellfoxSyncRunDO>
    fun findByJobOrderByStartedAtDesc(job: String, pageable: Pageable): List<SellfoxSyncRunDO>
    fun existsByJobAndStatus(job: String, status: String): Boolean
    fun findByStatus(status: String): List<SellfoxSyncRunDO>
}
