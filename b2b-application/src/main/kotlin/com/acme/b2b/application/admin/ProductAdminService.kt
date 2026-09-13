package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.AdminProductDTO
import com.acme.b2b.application.admin.dto.TierPriceDTO
import com.acme.b2b.application.admin.dto.WarehouseStockDTO
import com.acme.b2b.application.catalog.ProductAssembler
import com.acme.b2b.application.catalog.SortParser
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.application.catalog.dto.ProductDTO
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.domain.customer.CustomerTierRepository
import com.acme.b2b.types.DiscountPercent
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
    private val stockBreakdown: VariantStockBreakdownRepository,
) {

    fun list(query: AdminProductQuery): PagedDTO<ProductDTO> {
        val criteria = ProductSearchCriteria(
            text = query.search?.takeIf { it.isNotBlank() },
            // An admin sees drafts and archived products; a dealer never does.
            onlyVisible = false,
            visibility = query.visibility?.takeIf { it.isNotBlank() }?.let { parseVisibility(it) },
            sort = SortParser.parse(query.sort, query.direction),
        )
        val page = products.search(criteria, Page(query.page, query.size))
        val names = categoryNames()
        // One query for the whole page, not one per product.
        val anchorId = tiers.anchor().id
        val priced = tierPrices
            .findAllFor(page.content.flatMap { product -> product.variants.map { it.sku } })
            .filter { it.tierId == anchorId }
            .map { it.sku }
            .toSet()

        return PagedDTO(
            content = page.content.map { toDto(it, names, sellable = it.isSellable(priced)) },
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            page = page.page.number,
            size = page.page.size,
        )
    }

    fun findById(id: Long): AdminProductDTO? {
        val product = products.findById(id) ?: return null
        return detailOf(product)
    }

    /**
     * The detail response, including whether the product could be sold at all and why
     * not — the edit form is the one screen that can do something about it.
     */
    private fun detailOf(product: Product) = AdminProductDTO(
        product = toDto(product, categoryNames(), sellable = product.isSellable(pricedSkusOf(product))),
        tierPrices = priceBookOf(product),
        stockByWarehouse = stockOf(product),
    )

    /** Where this product's stock sits. Only assembled for the detail view that shows it. */
    private fun stockOf(product: Product): List<WarehouseStockDTO> =
        stockBreakdown.findBySkus(product.variants.map { it.sku.value })
            .map { line ->
                WarehouseStockDTO(
                    sku = line.sku,
                    warehouseId = line.warehouseId,
                    warehouseName = line.warehouseName.ifBlank { "Warehouse ${line.warehouseId}" },
                    available = line.available,
                    incoming = line.incoming,
                    syncedAt = line.syncedAt.toString(),
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
            baseWholesalePrice = existing.baseWholesalePrice,
            locationCode = command.locationCode,
            attributes = command.attributes,
            visibility = parseVisibility(command.visibility),
            categoryIds = command.categoryIds,
            primaryCategoryId = command.primaryCategoryId,
            // Not from the command: a product's gallery is attached and ordered through
            // the image endpoints, so a save must leave it exactly as it found it.
            images = existing.images,
            variants = existing.variants.map { variant ->
                applyMapPrice(variant, command.variantMapPrices)
            },
        )

        val saved = products.save(updated)
        command.tierPrices.takeIf { it.isNotEmpty() }?.let { savePriceBook(saved, it) }

        // After the prices are written, so one save can set both. The transaction rolls
        // back on refusal.
        requireSellableIfVisible(saved)

        return detailOf(refreshReferencePrice(saved))
    }

    /**
     * Hides a product from dealers, or brings it back. The portal's only way to remove
     * something from the catalog — the product, its pricing and its history all survive,
     * so reactivating restores exactly what was there.
     */
    @Transactional
    fun setActive(id: Long, active: Boolean): AdminProductDTO {
        val existing = products.findById(id)
            ?: throw NoSuchElementException("No product with id $id")

        val visibility = if (active) ProductVisibility.VISIBLE else ProductVisibility.HIDDEN
        if (existing.visibility == visibility) {
            return detailOf(existing)
        }

        // The product as it would be, not as it is — `existing` is still hidden here, so
        // asking it whether it may be visible answers about the wrong thing.
        val intended = existing.withVisibility(visibility)
        requireSellableIfVisible(intended)

        val saved = products.save(intended)
        return detailOf(saved)
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

    /**
     * Refuses to leave a product visible that no dealer could buy from, per
     * [Product.isSellable]. Both routes that can set visibility go through here — a rule
     * enforced on only one of two is not enforced.
     */
    private fun requireSellableIfVisible(product: Product) {
        if (!product.isVisible) return
        val reason = product.unsellableReason(pricedSkusOf(product)) ?: return
        throw UseCaseViolation(
            when (reason) {
                UnsellableReason.NOTHING_ON_SALE ->
                    "Every SKU of ${product.spuCode} is discontinued, so there is nothing for a " +
                        "dealer to buy. The supplier has to list it again before it can be shown."
                UnsellableReason.NO_DEFAULT_PRICE ->
                    "A SKU of ${product.spuCode} still on sale has no default price. Every other " +
                        "tier is worked out from that figure, so the SKU has no price at all."
            }
        )
    }

    /**
     * Every SKU of this product against every tier — the complete picture, not just the
     * rows someone typed.
     *
     * The screen has to render a price for each pairing either way, and building it here
     * means one place decides what a tier pays. Left to the client, the discount
     * arithmetic would be stated twice and would eventually disagree with the dealer's.
     */
    /**
     * Keeps the product's search figure in step with its prices.
     *
     * Nothing prices anything from it. Filtering and sorting a result list needs one
     * number per product, and the cheapest default price among the SKUs on sale is the
     * one the dealer's card already shows as "from $X".
     */
    private fun refreshReferencePrice(product: Product): Product {
        val anchorId = tiers.anchor().id
        val onSale = product.onSaleVariants.map { it.sku }.toSet()
        val cheapest = tierPrices.findAllFor(onSale)
            .filter { it.tierId == anchorId && it.sku in onSale }
            .minOfOrNull { it.price }
            ?: Money.ZERO
        if (cheapest == product.baseWholesalePrice) return product
        return products.save(product.withReferencePrice(cheapest))
    }

    /**
     * SKUs carrying a default price. Every other tier is worked out from it, so a SKU
     * without one has no price at any tier.
     */
    private fun pricedSkusOf(product: Product): Set<SkuCode> {
        val anchorId = tiers.anchor().id
        return tierPrices.findAllFor(product.variants.map { it.sku })
            .filter { it.tierId == anchorId }
            .map { it.sku }
            .toSet()
    }

    private fun priceBookOf(product: Product): List<TierPriceDTO> {
        val allTiers = tiers.findAll()
        val anchor = tiers.anchor()
        val book = tierPrices.findAllFor(product.variants.map { it.sku })
        val stated = book.associateBy { it.sku to it.tierId }

        return product.variants.flatMap { variant ->
            allTiers.map { tier ->
                val own = stated[variant.sku to tier.id]
                val standard = PricingPolicy.standardPrice(variant, tier, book, anchor)
                val price = own?.price ?: standard
                TierPriceDTO(
                    sku = variant.sku.value,
                    tierId = tier.id.value,
                    tierName = tier.name,
                    anchor = tier.anchor,
                    price = price?.amount,
                    standardPrice = standard?.amount,
                    discountPercent = tier.discount.value,
                    // The anchor's own price is always stated; nothing derives it.
                    customised = own != null && !tier.anchor,
                    breachesMap = price != null && PricingPolicy.breachesMap(variant, price),
                    minQty = own?.minQty?.value ?: 1,
                )
            }
        }
        // Variant order then tier order, taken from the iteration rather than a sort:
        // sizes are not lexical (S < M < L < XL), so sorting on the SKU string would
        // scramble them.
    }

    /**
     * The admin view shows list price rather than a tier price: there is no dealer in
     * this request to resolve one for. The price book each tier would pay is carried
     * separately, on AdminProductDTO.tierPrices.
     */
    private fun toDto(
        product: Product,
        categoryNames: Map<Long, String>,
        sellable: Boolean? = null,
    ): ProductDTO {
        // The admin catalogue shows each SKU's anchor price — what a Default dealer pays.
        val anchor = tiers.anchor()
        val book = tierPrices.findAllFor(product.variants.map { it.sku })
        val prices = product.variants.mapNotNull { variant ->
            PricingPolicy.resolve(variant, anchor, book, anchor)?.let { variant.sku.value to it }
        }.toMap()
        return ProductAssembler.toDTO(
            product, prices, categoryNames, sellable,
            unsellableReason = product.unsellableReason(pricedSkusOf(product))?.name,
        )
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

    private fun parseVisibility(raw: String): ProductVisibility =
        runCatching { ProductVisibility.valueOf(raw.uppercase()) }
            .getOrElse { throw UseCaseViolation("Unknown product visibility: $raw") }

}
