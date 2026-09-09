package com.acme.b2b.domain.catalog

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.types.SpuCode

/**
 * Port. Deals in aggregates, never in database rows — the DO type does not exist at
 * this layer. Implemented by infrastructure.
 */
interface ProductRepository {
    fun findById(id: Long): Product?
    fun findBySpuCode(spuCode: SpuCode): Product?
    fun search(criteria: ProductSearchCriteria, page: Page): PageOf<Product>
    fun save(product: Product): Product
    fun deleteById(id: Long)

    fun countAll(): Long
    fun countByStatus(status: ProductStatus): Long
}
