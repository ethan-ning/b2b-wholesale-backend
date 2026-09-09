package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.ProductStockRepository
import com.acme.b2b.domain.catalog.SkuStockUpdate
import com.acme.b2b.domain.sellfox.*
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Refreshes stock from Sellfox.
 *
 * Runs far more often than the catalog job because it is far cheaper: only the selected
 * warehouses are read, and the endpoint does take a warehouse filter.
 *
 * Stock is summed across the selected warehouses. Our product_variant carries one
 * available figure and a dealer only asks whether it can ship — which warehouse it ships
 * from is a fulfilment concern the portal does not surface. That the sum is over
 * *selected* warehouses is what keeps China-only stock out of a US availability number.
 */
@Service
class SellfoxInventorySyncService(
    private val sellfox: SellfoxInventoryPort,
    private val scope: SellfoxScopeRepository,
    private val stock: ProductStockRepository,
    private val runner: SyncRunner,
    private val clock: Clock,
) {

    fun sync(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun =
        runner.run(SellfoxJob.INVENTORY, trigger, triggeredBy) { counts ->
            val now = clock.instant()

            // Refreshed first so the picker is populated before anything can be chosen.
            scope.refreshWarehouses(sellfox.listWarehouses(), now)

            val warehouseIds = scope.selectedWarehouseIds()
            if (warehouseIds.isEmpty()) {
                return@run "Discovered ${scope.warehouses().size} warehouses. " +
                    "None selected yet, so no stock was read."
            }

            val totals = mutableMapOf<String, Totals>()
            warehouseIds.forEach { warehouseId ->
                val rows = sellfox.listStock(warehouseId)
                counts.read(rows.size)
                rows.forEach { row ->
                    totals.getOrPut(row.sku) { Totals() }.add(row.available, row.incoming)
                }
            }

            val updates = totals.map { (sku, t) -> SkuStockUpdate(sku, t.available, t.incoming, now) }
            val matched = stock.applyStock(updates)

            counts.wrote(matched)
            // Sellfox holds far more SKUs than the portal carries; the rest are not
            // errors, they are simply out of catalog.
            counts.skipped(totals.size - matched)

            "${totals.size} SKUs across ${warehouseIds.size} warehouse(s); " +
                "$matched matched a catalog SKU"
        }

    private class Totals {
        var available = 0; private set
        var incoming = 0; private set
        fun add(available: Int, incoming: Int) {
            this.available += available
            this.incoming += incoming
        }
    }
}
