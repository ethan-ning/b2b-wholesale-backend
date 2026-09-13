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
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.domain.customer.CustomerTierRepository

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
    private val tiers: CustomerTierRepository,
    private val dealerContext: DealerContext,
) {

    fun search(query: ProductQuery): PagedDTO<ProductDTO> {
        val tier = currentTier()
        val criteria = ProductSearchCriteria(
            text = query.search?.takeIf { it.isNotBlank() },
            categoryId = query.categoryId,
            priceMin = query.priceMin?.let { Money.of(it) },
            priceMax = query.priceMax?.let { Money.of(it) },
            sort = SortParser.parse(query.sort, null),
        )

        val page = products.search(criteria, Page(query.page, query.size))
        val categoryNames = categoryNames()
        val priced = page.map { product -> toDTO(product, tier, categoryNames) }

        return PagedDTO(
            content = priced.content,
            totalElements = priced.totalElements,
            totalPages = priced.totalPages,
            page = priced.page.number,
            size = priced.page.size,
        )
    }

    /**
     * A product by its code, or null when a dealer has no business seeing it.
     *
     * Search filters on visibility, but this route did not, so a hidden product came back
     * with a 200 to anyone who knew the code — and codes are guessable from the ones on
     * display. Hidden has to mean hidden on every route, not just the one people browse.
     *
     * A product with nothing on sale is treated the same way: there is nothing to buy, and
     * an empty SKU table is a worse answer than not found.
     */
    fun findBySpuCode(spuCode: String): ProductDTO? {
        val product = products.findBySpuCode(SpuCode(spuCode)) ?: return null
        if (!product.isVisible || product.onSaleVariants.isEmpty()) return null
        return toDTO(product, currentTier(), categoryNames())
    }

    fun categoryTree(): List<CategoryDTO> = categories.findTree().map { ProductAssembler.toDTO(it) }

    /**
     * The dealer's own tier, with the discount that prices everything they have not been
     * quoted for. Looked up rather than carried on the token: a tier's rate can change
     * between a dealer signing in and asking a price.
     */
    private fun currentTier(): CustomerTier {
        val tierId = dealerContext.currentTierId()
        return tiers.findById(tierId)
            ?: throw IllegalStateException("Dealer is on tier ${tierId.value}, which does not exist")
    }

    /**
     * Prices every SKU of [product] for [tier] in one repository round trip, then
     * assembles. Pricing a page is one query for the whole page, not one per SKU.
     */
    private fun toDTO(product: Product, tier: CustomerTier, categoryNames: Map<Long, String>): ProductDTO {
        val onSale = product.onSaleVariants
        val priceBook = tierPrices.findFor(onSale.map { it.sku }, tier.id)
        val resolved: Map<String, ResolvedPrice> = onSale.associate { variant ->
            variant.sku.value to PricingPolicy.resolve(product, variant, tier, priceBook)
        }
        return ProductAssembler.toDTO(product, resolved, categoryNames, variants = onSale)
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

}
