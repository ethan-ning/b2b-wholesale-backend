package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.SkuStockDTO
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.inventory.StockQueryPort
import com.acme.b2b.domain.inventory.StockSearchCriteria
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Stock, read-only by design. The ERP owns these numbers, and an admin-entered
 * correction would be reverted by the next sync while looking like it had stuck.
 * Corrections are made in the ERP.
 */
@Service
@Transactional(readOnly = true)
class InventoryQueryService(
    private val stock: StockQueryPort,
) {

    fun list(query: StockQuery): PagedDTO<SkuStockDTO> {
        val page = stock.search(
            StockSearchCriteria(
                text = query.search?.takeIf { it.isNotBlank() },
                lowStockOnly = query.lowStockOnly,
            ),
            Page(query.page, query.size),
        )

        return PagedDTO(
            content = page.content.map {
                SkuStockDTO(
                    variantId = it.variantId,
                    sku = it.sku.value,
                    spuCode = it.spuCode.value,
                    productName = it.productName,
                    variantValue = it.variantValue,
                    availableStock = it.stock.available,
                    incomingStock = it.stock.incoming,
                    lowStock = it.stock.isLow,
                    outOfStock = it.stock.isOutOfStock,
                    lastSyncedAt = it.stock.lastSyncedAt.toString(),
                )
            },
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            page = page.page.number,
            size = page.page.size,
        )
    }
}
