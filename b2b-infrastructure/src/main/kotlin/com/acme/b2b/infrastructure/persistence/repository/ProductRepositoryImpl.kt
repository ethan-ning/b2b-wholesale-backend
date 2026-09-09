package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.pricing.PricingPolicy
import com.acme.b2b.domain.pricing.TierPriceRepository
import com.acme.b2b.infrastructure.persistence.converter.ProductDataConverter
import com.acme.b2b.infrastructure.persistence.entity.ProductCategoryDO
import com.acme.b2b.infrastructure.persistence.entity.ProductDO
import com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository
import com.acme.b2b.types.Money
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.TierId
import java.time.Instant
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
    private val variants: ProductVariantJpaRepository,
    private val converter: ProductDataConverter,
    private val tierPrices: TierPriceRepository,
    private val categories: CategoryRepository,
) : ProductRepository {

    override fun findById(id: Long): Product? =
        jpa.findById(id).orElse(null)?.let { converter.toDomain(it) }

    override fun findBySpuCode(spuCode: SpuCode): Product? =
        jpa.findBySpuCode(spuCode.value)?.let { converter.toDomain(it) }

    override fun findByCategoryId(categoryId: Long): List<Product> =
        jpa.findByCategoryId(categoryId).map { converter.toDomain(it) }

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

        found = sorted(filteredByPrice(found, criteria), criteria.sort)

        val window = found.drop(page.offset).take(page.size)
        return PageOf(window, found.size.toLong(), page)
    }

    /**
     * Inserts a product and its SKUs. Only the Sellfox import reaches here — [save] is
     * what portal edits go through, and it refuses to create.
     */
    override fun create(product: Product): Product {
        val row = ProductDO()
        converter.applyTo(row, product)
        converter.applySyncedIdentity(row, product)
        converter.replaceVariants(row, product)
        row.source = SELLFOX_SOURCE
        row.createdAt = Instant.now()
        return converter.toDomain(jpa.save(row))
    }

    /**
     * Writes back only what Sellfox owns. The portal-owned columns on the row are never
     * touched, so a nightly sync cannot undo a price an admin set this afternoon.
     */
    override fun saveSynced(incoming: Product, existing: Product): Product {
        val row = jpa.findById(requireNotNull(existing.id)).orElseThrow {
            IllegalStateException("Product ${existing.spuCode} vanished mid-sync")
        }
        converter.applySyncedIdentity(row, incoming)
        converter.replaceVariants(row, incoming)
        row.source = SELLFOX_SOURCE
        return converter.toDomain(jpa.save(row))
    }

    /**
     * Compares in memory rather than with a NOT IN. The in-scope set is the whole imported
     * catalog — thousands of codes once a couple of category groups are selected — and
     * that is the wrong thing to send to Postgres as a literal list. The other side, the
     * active Sellfox-sourced rows, is the small one.
     */
    override fun deactivateSyncedProductsNotIn(spuCodes: Set<SpuCode>): Int {
        val keep = spuCodes.map { it.value }.toSet()
        val stale = jpa.findBySourceAndStatus(SELLFOX_SOURCE, ProductStatus.ACTIVE.name)
            .filterNot { it.spuCode in keep }
        stale.forEach { it.status = ProductStatus.INACTIVE.name }
        jpa.saveAll(stale)
        return stale.size
    }

    override fun skusFiledElsewhere(spuCode: SpuCode, skus: Set<String>): Set<String> {
        if (skus.isEmpty()) return emptySet()
        return variants.findBySkuIn(skus)
            .filter { it.product?.spuCode != spuCode.value }
            .map { it.sku }
            .toSet()
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

    /**
     * A product matches a price range if any of its SKUs does, and sorts on its cheapest
     * SKU — the "from" figure the dealer sees on the card.
     */
    /**
     * Tier is not part of the criteria yet, so list price is both the filter and the sort
     * key. Tracked in ARCHITECTURE.md alongside the price view that replaces this.
     */
    private fun filteredByPrice(products: List<Product>, criteria: ProductSearchCriteria): List<Product> {
        val min = criteria.priceMin
        val max = criteria.priceMax
        if (min == null && max == null) return products

        return products.filter { product ->
            val price = product.baseWholesalePrice
            (min == null || price >= min) && (max == null || price <= max)
        }
    }

    /**
     * Applied in memory because the window is taken here too — the whole result set is
     * already loaded. That is the same constraint the price view is meant to lift.
     */
    private companion object {
        /** Matches product.source; rows the portal owns are never touched by a sync. */
        const val SELLFOX_SOURCE = "SELLFOX"
    }

    private fun sorted(products: List<Product>, sort: ProductSort): List<Product> {
        val byField: Comparator<Product> = when (sort.field) {
            ProductSortField.SPU_CODE -> compareBy { it.spuCode.value }
            ProductSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ProductSortField.PRICE -> compareBy { it.baseWholesalePrice.amount }
            ProductSortField.BRAND -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.brand.orEmpty() }
        }
        val directed = if (sort.direction == SortDirection.ASC) byField else byField.reversed()

        // Unbranded products sort last in both directions. Reversing the whole ordering
        // would float them to the top on a descending sort, which reads as a bug rather
        // than as an ordering choice.
        val comparator =
            if (sort.field == ProductSortField.BRAND) {
                compareBy<Product> { it.brand.isNullOrBlank() }.then(directed)
            } else {
                directed
            }

        return products.sortedWith(comparator)
    }
}
