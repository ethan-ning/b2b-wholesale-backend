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

    fun countAll(): Long
    fun countByStatus(status: ProductStatus): Long
}
