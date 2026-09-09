package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.DashboardStatsDTO
import com.acme.b2b.domain.catalog.ProductRepository
import com.acme.b2b.domain.catalog.ProductVisibility
import com.acme.b2b.domain.customer.CustomerRepository
import com.acme.b2b.domain.customer.CustomerStatus
import com.acme.b2b.domain.inventory.StockQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The landing screen's counters. Each is a counting query rather than a loaded
 * collection — the dashboard should not get slower as the catalog grows.
 */
@Service
@Transactional(readOnly = true)
class DashboardService(
    private val products: ProductRepository,
    private val customers: CustomerRepository,
    private val stock: StockQueryPort,
) {
    fun stats() = DashboardStatsDTO(
        totalProducts = products.countAll(),
        activeProducts = products.countByVisibility(ProductVisibility.VISIBLE),
        totalCustomers = customers.countAll(),
        activeCustomers = customers.countByStatus(CustomerStatus.ACTIVE),
        lowStockAlerts = stock.countLowStock(),
        outOfStockCount = stock.countOutOfStock(),
    )
}
