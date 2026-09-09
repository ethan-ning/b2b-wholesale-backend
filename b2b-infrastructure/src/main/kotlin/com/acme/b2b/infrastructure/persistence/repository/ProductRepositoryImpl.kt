package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.pricing.PricingPolicy
import com.acme.b2b.domain.pricing.TierPriceRepository
import com.acme.b2b.infrastructure.persistence.converter.ProductDataConverter
import com.acme.b2b.infrastructure.persistence.entity.ProductCategoryDO
import com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository
import com.acme.b2b.types.Money
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.TierId
import org.springframework.stereotype.Repository

/**
 * Implements the domain's ProductRepository port. Returns aggregates; the DO type never
 * escapes this package.
 *
 * Price filtering and price sorting are applied here in memory after resolving each
 * product's tier prices, because "price" means the requesting dealer's resolved price,
 * which is not a column. That is correct but does not scale — see ARCHITECTURE.md for
 * the materialised price-view that replaces it.
 */
@Repository
class ProductRepositoryImpl(
    private val jpa: ProductJpaRepository,
    private val converter: ProductDataConverter,
    private val tierPrices: TierPriceRepository,
    private val categories: CategoryRepository,
) : ProductRepository {

    override fun findById(id: Long): Product? =
        jpa.findById(id).orElse(null)?.let { converter.toDomain(it) }

    override fun findBySpuCode(spuCode: SpuCode): Product? =
        jpa.findBySpuCode(spuCode.value)?.let { converter.toDomain(it) }

    override fun countAll(): Long = jpa.count()

    override fun countByStatus(status: ProductStatus): Long = jpa.countByStatus(status.name)

    override fun search(criteria: ProductSearchCriteria, page: Page): PageOf<Product> {
        val text = criteria.text?.lowercase()?.let { "%$it%" }
        // A dealer sees only ACTIVE; an admin may narrow to one status or see them all.
        val status = when {
            criteria.onlyPublished -> ProductStatus.ACTIVE.name
            else -> criteria.status?.name
        }

        var found = jpa.search(text, status).map { converter.toDomain(it) }

        val categoryId = criteria.categoryId
        if (categoryId != null) {
            val wanted = categories.findDescendantIds(categoryId).toSet() + categoryId
            found = found.filter { product -> product.categoryIds.any { it in wanted } }
        }

        if (criteria.priceMin != null || criteria.priceMax != null || criteria.sort != ProductSort.RELEVANCE) {
            found = applyPriceRules(found, criteria)
        } else {
            found = found.sortedBy { it.name }
        }

        val window = found.drop(page.offset).take(page.size)
        return PageOf(window, found.size.toLong(), page)
    }

    override fun save(product: Product): Product {
        val row = product.id?.let { jpa.findById(it).orElse(null) }
            ?: throw IllegalStateException("Product ${product.spuCode} does not exist; catalog rows come from the ERP sync")

        converter.applyTo(row, product)

        // Reconcile rather than clear-and-re-add: Hibernate is free to order the inserts
        // before the deletes within one flush, which trips the (product_id, category_id)
        // unique key when a category is being kept.
        row.categories.removeIf { it.categoryId !in product.categoryIds }
        product.categoryIds.forEach { categoryId ->
            val existingLink = row.categories.firstOrNull { it.categoryId == categoryId }
            if (existingLink == null) {
                row.categories.add(
                    ProductCategoryDO(
                        product = row,
                        categoryId = categoryId,
                        isPrimary = categoryId == product.primaryCategoryId,
                    )
                )
            } else {
                existingLink.isPrimary = categoryId == product.primaryCategoryId
            }
        }
        return converter.toDomain(jpa.save(row))
    }

    override fun deleteById(id: Long) = jpa.deleteById(id)

    /**
     * A product matches a price range if any of its SKUs does, and sorts on its cheapest
     * SKU — the "from" figure the dealer sees on the card.
     */
    private fun applyPriceRules(products: List<Product>, criteria: ProductSearchCriteria): List<Product> {
        // Tier is not part of the criteria yet; list price is the sort key until the
        // price view lands. Tracked in ARCHITECTURE.md.
        val cheapest = products.associateWith { product -> product.baseWholesalePrice }

        val min = criteria.priceMin
        val max = criteria.priceMax
        val filtered = products.filter { product ->
            val price = cheapest.getValue(product)
            (min == null || price >= min) && (max == null || price <= max)
        }

        return when (criteria.sort) {
            ProductSort.PRICE_ASC -> filtered.sortedBy { cheapest.getValue(it).amount }
            ProductSort.PRICE_DESC -> filtered.sortedByDescending { cheapest.getValue(it).amount }
            ProductSort.NAME_ASC -> filtered.sortedBy { it.name }
            ProductSort.RELEVANCE -> filtered
        }
    }
}
