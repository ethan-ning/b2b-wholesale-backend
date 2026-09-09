package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.AdminProductDTO
import com.acme.b2b.application.admin.dto.TierPriceDTO
import com.acme.b2b.application.catalog.ProductAssembler
import com.acme.b2b.application.catalog.SortParser
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.application.catalog.dto.ProductDTO
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.customer.CustomerTierRepository
import com.acme.b2b.domain.pricing.PricingPolicy
import com.acme.b2b.domain.pricing.TierPrice
import com.acme.b2b.domain.pricing.TierPriceRepository
import com.acme.b2b.types.*
import java.math.BigDecimal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Product management for the admin portal.
 *
 * Only portal-owned fields are writable. The ERP owns the product's identity, so an
 * update deliberately cannot reach name, brand, description, SPU code or variant axis,
 * and the command type has no field for them — rejection is by shape, not by a check
 * someone can forget.
 */
@Service
@Transactional(readOnly = true)
class ProductAdminService(
    private val products: ProductRepository,
    private val tierPrices: TierPriceRepository,
    private val categories: CategoryRepository,
    private val tiers: CustomerTierRepository,
) {

    fun list(query: AdminProductQuery): PagedDTO<ProductDTO> {
        val criteria = ProductSearchCriteria(
            text = query.search?.takeIf { it.isNotBlank() },
            // An admin sees drafts and archived products; a dealer never does.
            onlyPublished = false,
            status = query.status?.takeIf { it.isNotBlank() }?.let { parseStatus(it) },
            sort = SortParser.parse(query.sort, query.direction),
        )
        val page = products.search(criteria, Page(query.page, query.size))
        val names = categoryNames()

        return PagedDTO(
            content = page.content.map { toDto(it, names) },
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            page = page.page.number,
            size = page.page.size,
        )
    }

    fun findById(id: Long): AdminProductDTO? {
        val product = products.findById(id) ?: return null
        return AdminProductDTO(
            product = toDto(product, categoryNames()),
            tierPrices = priceBookOf(product),
        )
    }

    @Transactional
    fun update(id: Long, command: UpdateProductCommand): AdminProductDTO {
        val existing = products.findById(id)
            ?: throw NoSuchElementException("No product with id $id")

        command.categoryIds.forEach { categoryId ->
            categories.findById(categoryId) ?: throw UseCaseViolation("No such category: $categoryId")
        }
        if (command.primaryCategoryId != null && command.primaryCategoryId !in command.categoryIds) {
            throw UseCaseViolation("The primary category must be one of the assigned categories")
        }

        val updated = Product(
            id = existing.id,
            // Carried over untouched: the ERP owns these.
            spuCode = existing.spuCode,
            name = existing.name,
            brand = existing.brand,
            description = existing.description,
            variantAxis = existing.variantAxis,
            // Ours.
            baseWholesalePrice = Money.of(command.baseWholesalePrice),
            locationCode = command.locationCode,
            attributes = command.attributes,
            status = parseStatus(command.status),
            categoryIds = command.categoryIds,
            primaryCategoryId = command.primaryCategoryId,
            imageUrls = command.imageUrls,
            variants = existing.variants.map { variant ->
                applyMapPrice(variant, command.variantMapPrices)
            },
        )

        val saved = products.save(updated)
        command.tierPrices.takeIf { it.isNotEmpty() }?.let { savePriceBook(saved, it) }

        return AdminProductDTO(toDto(saved, categoryNames()), priceBookOf(saved))
    }

    @Transactional
    fun delete(id: Long) {
        products.findById(id) ?: throw NoSuchElementException("No product with id $id")
        products.deleteById(id)
    }

    /**
     * MAP is stated per SKU and never inherited, so an absent entry means "leave it",
     * not "clear it" — a partial form submission must not wipe the others.
     */
    private fun applyMapPrice(variant: ProductVariant, updates: Map<Long, BigDecimal?>): ProductVariant {
        val variantId = variant.id ?: return variant
        if (variantId !in updates) return variant
        return ProductVariant(
            id = variant.id,
            sku = variant.sku,
            variantValue = variant.variantValue,
            packQuantity = variant.packQuantity,
            mapPrice = updates[variantId]?.let { Money.of(it) },
            upc = variant.upc,
            weight = variant.weight,
            sortOrder = variant.sortOrder,
            active = variant.active,
            stock = variant.stock,
        )
    }

    /** Replaces the price book for each SKU the command mentions, leaving others alone. */
    private fun savePriceBook(product: Product, entries: List<TierPriceEntry>) {
        entries.forEach { entry ->
            val sku = SkuCode(entry.sku)
            product.variant(sku)
                ?: throw UseCaseViolation("${entry.sku} is not a SKU of ${product.spuCode}")
            tiers.findById(TierId(entry.tierId))
                ?: throw UseCaseViolation("No such pricing tier: ${entry.tierId}")
        }

        entries.groupBy { it.sku }.forEach { (sku, rows) ->
            tierPrices.replaceFor(
                SkuCode(sku),
                rows.map {
                    TierPrice(SkuCode(it.sku), TierId(it.tierId), Money.of(it.price), Quantity(it.minQty))
                },
            )
        }
    }

    private fun priceBookOf(product: Product): List<TierPriceDTO> {
        val names = tiers.findAll().associate { it.id.value to it.name }
        val order = product.variants.map { it.sku }.withIndex().associate { (i, sku) -> sku to i }

        return tierPrices.findAllFor(product.variants.map { it.sku })
            .map { row ->
                TierPriceDTO(
                    sku = row.sku.value,
                    tierId = row.tierId.value,
                    tierName = names[row.tierId.value] ?: "",
                    price = row.price.amount,
                    minQty = row.minQty.value,
                )
            }
            // Variant order, not SKU string: sizes are not lexical (S < M < L < XL).
            .sortedWith(compareBy({ order[SkuCode(it.sku)] ?: Int.MAX_VALUE }, { it.tierId }, { it.minQty }))
    }

    /**
     * The admin view shows list price rather than a tier price: there is no dealer in
     * this request to resolve one for.
     */
    private fun toDto(product: Product, categoryNames: Map<Long, String>): ProductDTO {
        val listPrices = product.variants.associate { variant ->
            variant.sku.value to PricingPolicy.resolve(
                product, variant, LIST_PRICE_TIER, priceBook = emptyList(),
            )
        }
        return ProductAssembler.toDTO(product, listPrices, categoryNames)
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

    private fun parseStatus(raw: String): ProductStatus =
        runCatching { ProductStatus.valueOf(raw.uppercase()) }
            .getOrElse { throw UseCaseViolation("Unknown product status: $raw") }

    private companion object {
        /** Any tier; with an empty price book the policy falls through to list price. */
        val LIST_PRICE_TIER = TierId(1)
    }
}
