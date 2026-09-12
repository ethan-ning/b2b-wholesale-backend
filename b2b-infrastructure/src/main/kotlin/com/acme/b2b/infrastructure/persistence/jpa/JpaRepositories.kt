package com.acme.b2b.infrastructure.persistence.jpa

import com.acme.b2b.infrastructure.persistence.entity.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Page as SpringPage
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
        WHERE (:visibility IS NULL OR p.visibility = :visibility)
          AND (:text IS NULL
               OR LOWER(p.name) LIKE :text
               OR LOWER(p.spuCode) LIKE :text
               OR LOWER(COALESCE(p.brand, '')) LIKE :text
               OR EXISTS (SELECT 1 FROM ProductVariantDO v WHERE v.product = p AND LOWER(v.sku) LIKE :text))
        """
    )
    fun search(
        @Param("text") text: String?,
        @Param("visibility") visibility: String?,
    ): List<ProductDO>

    fun countByVisibility(visibility: String): Long
    fun findBySourceAndVisibility(source: String, visibility: String): List<ProductDO>
    fun findBySourceIn(sources: Collection<String>): List<ProductDO>

    @Query("SELECT DISTINCT p FROM ProductDO p JOIN p.categories c WHERE c.categoryId = :categoryId")
    fun findByCategoryId(@Param("categoryId") categoryId: Long): List<ProductDO>

    /**
     * Removes products by id, without the entity cascade — which would reach into
     * ProductDO.variants and take a SKU that has just moved elsewhere. The schema already
     * cascades what genuinely belongs to a product.
     */
    @Modifying
    @Query("DELETE FROM ProductDO p WHERE p.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: Collection<Long>): Int
}

/**
 * The stock read side. Projects straight to a row rather than loading ProductDO graphs —
 * the admin's stock screen is a flat list across every SKU, and hydrating aggregates to
 * render it would be slower and a misuse of the aggregate.
 */
/**
 * The per-warehouse stock rows behind each SKU's total. Joined to the warehouse registry
 * on read so the admin sees a name rather than an id; a warehouse that has left the scope
 * is gone from the registry, so the name is left blank rather than the row dropped.
 */
interface VariantWarehouseStockJpaRepository : JpaRepository<VariantWarehouseStockDO, VariantWarehouseStockId> {

    fun deleteBySkuIn(skus: Collection<String>)

    @Query(
        """
        SELECT s.sku, s.warehouseId, COALESCE(w.name, ''), s.available, s.incoming, s.syncedAt
        FROM VariantWarehouseStockDO s
        LEFT JOIN SellfoxWarehouseDO w ON w.warehouseId = s.warehouseId
        WHERE s.sku IN :skus
        ORDER BY s.sku, COALESCE(w.name, ''), s.warehouseId
        """
    )
    fun findLinesBySkuIn(@Param("skus") skus: Collection<String>): List<Array<Any>>
}

interface ProductVariantJpaRepository : JpaRepository<ProductVariantDO, Long> {
    fun findByProductId(productId: Long): List<ProductVariantDO>

    /** Products of this source that still hold a SKU. Asked of the database, not of ProductDO.variants. */
    @Query("SELECT DISTINCT v.product.id FROM ProductVariantDO v WHERE v.product.source = :source")
    fun productIdsHoldingSkus(@Param("source") source: String): List<Long>

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
    fun findByProductSourceAndStatus(source: String, status: String): List<ProductVariantDO>
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
    fun existsByEmail(email: String): Boolean
    fun countByRole(role: String): Long
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

interface SellfoxSkuLinkJpaRepository : JpaRepository<SellfoxSkuLinkDO, String>

interface SellfoxSyncRunJpaRepository : JpaRepository<SellfoxSyncRunDO, Long> {
    fun findAllByOrderByStartedAtDesc(pageable: Pageable): List<SellfoxSyncRunDO>
    fun existsByStatus(status: String): Boolean
    fun findByStatus(status: String): List<SellfoxSyncRunDO>
    fun findByStatusAndStartedAtBefore(status: String, startedAt: java.time.Instant): List<SellfoxSyncRunDO>
}

interface ImageJpaRepository : JpaRepository<ImageDO, Long> {
    fun findByObjectKey(objectKey: String): ImageDO?

    /**
     * The library screen's query. Both filters are expressed as EXISTS against the link
     * table rather than a join, so an image used by six products is still one row.
     */
    @Query(
        """
        SELECT i FROM ImageDO i
        WHERE (:unusedOnly = FALSE
               OR NOT EXISTS (SELECT 1 FROM ProductImageDO pi WHERE pi.image = i))
          AND (:term IS NULL
               OR LOWER(i.filename) LIKE :term
               OR EXISTS (SELECT 1 FROM ProductImageDO pi2 WHERE pi2.image = i
                          AND (LOWER(pi2.product.spuCode) LIKE :term
                               OR LOWER(pi2.product.name) LIKE :term)))
        ORDER BY i.uploadedAt DESC, i.id DESC
        """
    )
    fun search(
        @Param("term") term: String?,
        @Param("unusedOnly") unusedOnly: Boolean,
        pageable: Pageable,
    ): SpringPage<ImageDO>

    @Query("SELECT COUNT(i) FROM ImageDO i WHERE NOT EXISTS (SELECT 1 FROM ProductImageDO pi WHERE pi.image = i)")
    fun countUnused(): Long
}

interface ProductImageJpaRepository : JpaRepository<ProductImageDO, Long> {
    fun findByProductIdOrderBySortOrderAsc(productId: Long): List<ProductImageDO>
    fun findByImageId(imageId: Long): List<ProductImageDO>
    fun findByImageIdIn(imageIds: Collection<Long>): List<ProductImageDO>
    fun countByProductId(productId: Long): Long
    fun findByProductIdAndImageId(productId: Long, imageId: Long): ProductImageDO?
}
