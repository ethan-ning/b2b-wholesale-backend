package com.acme.b2b.domain.catalog

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.types.SpuCode

/**
 * Port. Deals in aggregates, never in database rows — the DO type does not exist at
 * this layer. Implemented by infrastructure.
 *
 * There is no delete: products belong to the ERP, and the portal's way of removing one
 * from the catalog is to deactivate it. A method nobody may legitimately call is better
 * absent than guarded.
 */
interface ProductRepository {
    fun findById(id: Long): Product?
    fun findBySpuCode(spuCode: SpuCode): Product?
    fun search(criteria: ProductSearchCriteria, page: Page): PageOf<Product>
    /** Everything filed under one category, so deleting it can unfile them first. */
    fun findByCategoryId(categoryId: Long): List<Product>
    fun save(product: Product): Product

    /** Creates a product that does not exist yet. Only the ERP import may call this. */
    fun create(product: Product): Product

    /**
     * Updates only what the ERP owns — name, description, axis, the SKU set — leaving
     * pricing, MAP, categories, images and attributes as the portal set them.
     *
     * The field-ownership rule made structural: [save] cannot write a synced field and
     * this cannot write a portal one, so neither path overwrites the other by accident.
     */
    fun saveSynced(incoming: Product, existing: Product): Product

    /**
     * Hides ERP-sourced products a full sync did not see — their category left the scope,
     * or the supplier dropped them. Returns how many changed.
     *
     * Only ERP-sourced rows are touched, so a product keyed in by hand is never swept up
     * by a sync it was not part of.
     */
    fun deactivateSyncedProductsNotIn(spuCodes: Set<SpuCode>): Int

    /**
     * Which of [skus] currently sit under a product other than [spuCode]. A SKU belongs to
     * exactly one product, so a regrouped SKU has to be moved rather than inserted; the
     * import asks first and leaves those to [ProductGroupingRepository].
     */
    fun skusFiledElsewhere(spuCode: SpuCode, skus: Set<String>): Set<String>

    fun countAll(): Long
    fun countByStatus(status: ProductStatus): Long
}
