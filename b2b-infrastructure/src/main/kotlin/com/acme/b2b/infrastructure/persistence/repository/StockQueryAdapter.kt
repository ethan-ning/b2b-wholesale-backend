package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.catalog.StockLevel
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.inventory.SkuStock
import com.acme.b2b.domain.inventory.StockQueryPort
import com.acme.b2b.domain.inventory.StockSearchCriteria
import com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class StockQueryAdapter(
    private val jpa: ProductVariantJpaRepository,
) : StockQueryPort {

    override fun search(criteria: StockSearchCriteria, page: Page): PageOf<SkuStock> {
        val text = criteria.text?.lowercase()?.let { "%$it%" }
        val found = jpa.searchStock(
            text = text,
            lowStockOnly = criteria.lowStockOnly,
            threshold = StockLevel.LOW_STOCK_THRESHOLD,
            pageable = PageRequest.of(page.number, page.size),
        )

        return PageOf(
            content = found.content.map { row ->
                val product = checkNotNull(row.product) { "A variant row must belong to a product" }
                SkuStock(
                    variantId = checkNotNull(row.id),
                    sku = SkuCode(row.sku),
                    spuCode = SpuCode(product.spuCode),
                    productName = product.name,
                    variantValue = row.variantValue,
                    stock = StockLevel(
                        available = row.availableStock,
                        incoming = row.incomingStock,
                        lastSyncedAt = row.stockSyncedAt ?: Instant.EPOCH,
                    ),
                )
            },
            totalElements = found.totalElements,
            page = page,
        )
    }

    /** Low means in stock but nearly out; zero is counted separately as out of stock. */
    override fun countLowStock(): Long =
        jpa.countByAvailableStockGreaterThanAndAvailableStockLessThan(0, StockLevel.LOW_STOCK_THRESHOLD)

    override fun countOutOfStock(): Long = jpa.countByAvailableStock(0)
}
