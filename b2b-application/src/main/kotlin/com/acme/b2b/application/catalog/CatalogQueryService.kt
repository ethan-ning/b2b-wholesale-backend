package com.acme.b2b.application.catalog

import com.acme.b2b.application.catalog.dto.CategoryDTO
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.application.catalog.dto.ProductDTO
import com.acme.b2b.application.support.DealerContext
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.pricing.PricingPolicy
import com.acme.b2b.domain.pricing.ResolvedPrice
import com.acme.b2b.domain.pricing.TierPriceRepository
import com.acme.b2b.types.Money
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.TierId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The dealer-facing catalog use cases: search, and product detail.
 *
 * Orchestration only — it loads aggregates through ports, asks the domain for prices,
 * and hands the result to the assembler. No business rule lives here; PricingPolicy
 * owns the pricing rule and Product owns its own invariants.
 */
@Service
@Transactional(readOnly = true)
class CatalogQueryService(
    private val products: ProductRepository,
    private val tierPrices: TierPriceRepository,
    private val categories: CategoryRepository,
    private val dealerContext: DealerContext,
) {

    fun search(query: ProductQuery): PagedDTO<ProductDTO> {
        val tierId = dealerContext.currentTierId()
        val criteria = ProductSearchCriteria(
            text = query.search?.takeIf { it.isNotBlank() },
            categoryId = query.categoryId,
            priceMin = query.priceMin?.let { Money.of(it) },
            priceMax = query.priceMax?.let { Money.of(it) },
            sort = parseSort(query.sort),
        )

        val page = products.search(criteria, Page(query.page, query.size))
        val categoryNames = categoryNames()
        val priced = page.map { product -> toDTO(product, tierId, categoryNames) }

        return PagedDTO(
            content = priced.content,
            totalElements = priced.totalElements,
            totalPages = priced.totalPages,
            page = priced.page.number,
            size = priced.page.size,
        )
    }

    fun findBySpuCode(spuCode: String): ProductDTO? {
        val product = products.findBySpuCode(SpuCode(spuCode)) ?: return null
        return toDTO(product, dealerContext.currentTierId(), categoryNames())
    }

    fun categoryTree(): List<CategoryDTO> = categories.findTree().map { ProductAssembler.toDTO(it) }

    /**
     * Prices every SKU of [product] for [tierId] in one repository round trip, then
     * assembles. Pricing a page is one query for the whole page, not one per SKU.
     */
    private fun toDTO(product: Product, tierId: TierId, categoryNames: Map<Long, String>): ProductDTO {
        val priceBook = tierPrices.findFor(product.variants.map { it.sku }, tierId)
        val resolved: Map<String, ResolvedPrice> = product.variants.associate { variant ->
            variant.sku.value to PricingPolicy.resolve(product, variant, tierId, priceBook)
        }
        return ProductAssembler.toDTO(product, resolved, categoryNames)
    }

    private fun categoryNames(): Map<Long, String> {
        val flat = mutableMapOf<Long, String>()
        fun walk(nodes: List<Category>) {
            nodes.forEach { node ->
                node.id?.let { flat[it] = node.name }
                walk(node.children)
            }
        }
        walk(categories.findTree())
        return flat
    }

    private fun parseSort(raw: String): ProductSort = when (raw.lowercase()) {
        "price_asc" -> ProductSort.PRICE_ASC
        "price_desc" -> ProductSort.PRICE_DESC
        "name_asc" -> ProductSort.NAME_ASC
        else -> ProductSort.RELEVANCE
    }
}
