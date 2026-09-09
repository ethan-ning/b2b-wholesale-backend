package com.acme.b2b.application.sellfox

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.sellfox.SellfoxScopeRepository
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.SyncMode
import com.acme.b2b.domain.sellfox.TriggerSource
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * The three ways to sync with Sellfox, over the one scope an admin chose.
 *
 * Orchestration only: each step is its own component, and this decides which of them a
 * given mode runs and in what order.
 */
@Service
class SellfoxSyncService(
    private val catalog: SellfoxCatalogImporter,
    private val regrouper: SpuRegrouper,
    private val stock: SellfoxStockUpdater,
    private val scope: SellfoxScopeRepository,
    private val runner: SyncRunner,
    private val clock: Clock,
) {

    /**
     * Import what is in scope, re-file it, hide what has left, then count what remains.
     *
     * The order is the whole design. Products first, so a SKU this run imports gets its
     * stock from the same run; regrouping next, because the import files one family at a
     * time and cannot move a SKU another product still owns; stock last, so a product on
     * its way out is not counted on its way past.
     */
    fun syncFull(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun {
        requireScopeChosen()
        return runner.run(SyncMode.FULL, trigger, triggeredBy) { counts ->
            val now = clock.instant()
            listOf(
                catalog.import(counts, now),
                regrouper.regroup(counts),
                stock.refresh(counts, now),
            ).joinToString(" ")
        }
    }

    /**
     * Stock only. Cheap enough to run hourly, which is the point: stock is the part that
     * moves between catalog changes.
     */
    fun syncInventory(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun {
        requireScopeChosen()
        return runner.run(SyncMode.INVENTORY, trigger, triggeredBy) { counts ->
            stock.refresh(counts, clock.instant())
        }
    }

    /**
     * Regrouping alone, touching no Sellfox endpoint.
     *
     * Not scheduled: between full runs its inputs do not change, so a cron would only
     * confirm the previous answer. What makes it worth running is a change to the
     * grouping rules, which is a deploy rather than an hour of the day.
     */
    fun regroup(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun =
        runner.run(SyncMode.REGROUP, trigger, triggeredBy) { counts -> regrouper.regroup(counts) }

    /**
     * Refuses a run that would import nothing.
     *
     * Both selections are required, not either: a category with no warehouse imports
     * products that read as out of stock, and a warehouse with no category counts a
     * catalog that is not there. The exception is a first run, when there is nothing to
     * choose from yet — that run is how the lists get filled.
     *
     * Public because a caller that dispatches the sync to another thread has to ask
     * before dispatching; an exception thrown on that thread has nowhere to go.
     */
    fun requireScopeChosen() {
        val knownCategories = scope.categories()
        val knownWarehouses = scope.warehouses()
        if (knownCategories.isEmpty() && knownWarehouses.isEmpty()) return

        val missing = buildList {
            if (knownCategories.isNotEmpty() && scope.selectedCategoryIds().isEmpty()) add("a category")
            if (knownWarehouses.isNotEmpty() && scope.selectedWarehouseIds().isEmpty()) add("a warehouse")
        }
        if (missing.isNotEmpty()) {
            throw UseCaseViolation("Select ${missing.joinToString(" and ")} to sync")
        }
    }
}
