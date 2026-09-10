package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.ProductStockRepository
import com.acme.b2b.domain.catalog.SkuStockUpdate
import com.acme.b2b.domain.catalog.WarehouseStock
import com.acme.b2b.domain.sellfox.SellfoxInventoryPort
import com.acme.b2b.domain.sellfox.SellfoxScopeRepository
import com.acme.b2b.domain.sellfox.SyncCounts
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Reads stock for the warehouses in scope and sums it per SKU.
 *
 * A variant carries one available figure, because a dealer only asks whether it can ship.
 * Summing over the *selected* warehouses is what keeps stock that cannot reach a US dealer
 * out of the number they see. The rows behind the sum are kept too — an admin asking why
 * a total looks wrong needs to see where it came from.
 */
@Component
class SellfoxStockUpdater(
    private val sellfox: SellfoxInventoryPort,
    private val scope: SellfoxScopeRepository,
    private val stock: ProductStockRepository,
) {

    fun refresh(counts: SyncCounts, now: Instant): String {
        // Refreshed first so the picker is populated before anything can be chosen.
        scope.refreshWarehouses(sellfox.listWarehouses(), now)

        val warehouseIds = scope.selectedWarehouseIds()
        if (warehouseIds.isEmpty()) {
            return "Discovered ${scope.warehouses().size} warehouses; none selected, so no stock read."
        }

        val totals = mutableMapOf<String, Totals>()
        warehouseIds.forEach { warehouseId ->
            val rows = sellfox.listStock(warehouseId)
            counts.read(rows.size)
            rows.forEach { row ->
                totals.getOrPut(row.sku) { Totals() }.add(warehouseId, row.available, row.incoming)
            }
        }

        val matched = stock.applyStock(
            totals.map { (sku, t) -> SkuStockUpdate(sku, t.available, t.incoming, now, t.byWarehouse) }
        )
        counts.wrote(matched)
        // Sellfox holds far more SKUs than the portal carries; the rest are not errors,
        // they are simply out of catalog.
        counts.skipped(totals.size - matched)

        val warehouses = if (warehouseIds.size == 1) "warehouse." else "warehouses."
        return "Stock for $matched of ${totals.size} SKUs across ${warehouseIds.size} $warehouses"
    }

    private class Totals {
        var available = 0; private set
        var incoming = 0; private set
        val byWarehouse = mutableListOf<WarehouseStock>()

        fun add(warehouseId: Long, available: Int, incoming: Int) {
            this.available += available
            this.incoming += incoming
            // Only warehouses Sellfox reported the SKU in. It returns no row for a
            // warehouse that has never held it, so an absent warehouse means "not stocked
            // here" rather than "zero here", and inventing the zero row would blur the two.
            byWarehouse += WarehouseStock(warehouseId, available, incoming)
        }
    }
}
