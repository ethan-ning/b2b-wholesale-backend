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

    /**
     * Creates a product that does not exist yet. Separate from [save] because the portal
     * must not create products — they come from the ERP — and only the Sellfox import
     * legitimately calls this. Two methods say that; one method with a flag does not.
     */
    fun create(product: Product): Product

    /**
     * Updates only what Sellfox owns — name, description, axis, and the SKU set — leaving
     * pricing, MAP, categories, images and attributes as the portal set them. [incoming]
     * carries the synced fields, [existing] is the row they land on.
     *
     * The split is the field-ownership rule made structural: [save] cannot write a synced
     * field and this cannot write a portal one, so neither path can overwrite the other's
     * work by accident.
     */
    fun saveSynced(incoming: Product, existing: Product): Product

    /**
     * Hides ERP-sourced products a full sync did not see — their category left the import
     * scope, or the supplier dropped them. Returns how many changed.
     *
     * Deactivated, never deleted: the pricing an admin set hangs off these rows, and a
     * category removed by mistake would otherwise cost all of it. Only Sellfox-sourced
     * rows are touched, so a product keyed in by hand is not swept up by a sync it was
     * never part of.
     */
    fun deactivateSyncedProductsNotIn(spuCodes: Set<SpuCode>): Int

    /**
     * Which of [skus] currently sit under a product other than [spuCode].
     *
     * A SKU belongs to exactly one product, so when the grouping changes it has to be
     * *moved*, not inserted — an import that tries to add it to its new product hits the
     * unique key and abandons the run. The import asks first and leaves those families to
     * the regroup step, which is the only path that can re-file a SKU.
     */
    fun skusFiledElsewhere(spuCode: SpuCode, skus: Set<String>): Set<String>

    fun countAll(): Long
    fun countByStatus(status: ProductStatus): Long
}
