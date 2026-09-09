package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.sellfox.SellfoxScopeRepository
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.SellfoxSyncRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What the admin screen reads and writes: the import scope, and the record of every run.
 * Triggering a sync goes to the sync services themselves.
 */
@Service
@Transactional(readOnly = true)
class SellfoxAdminService(
    private val scope: SellfoxScopeRepository,
    private val runs: SellfoxSyncRunRepository,
) {

    fun scope(): SellfoxScopeView = SellfoxScopeView(
        categories = scope.categories(),
        warehouses = scope.warehouses(),
    )

    fun history(limit: Int): List<SellfoxSyncRun> = runs.recent(limit.coerceIn(1, MAX_HISTORY))

    /** True while a run is in flight, so the trigger button can say so before it 409s. */
    fun running(): Boolean = runs.isRunning()

    @Transactional
    fun selectCategory(cid: String, selected: Boolean) = scope.setCategorySelected(cid, selected)

    @Transactional
    fun selectWarehouse(warehouseId: Long, selected: Boolean) =
        scope.setWarehouseSelected(warehouseId, selected)

    private companion object {
        const val MAX_HISTORY = 200
    }
}

data class SellfoxScopeView(
    val categories: List<com.acme.b2b.domain.sellfox.SellfoxCategoryScope>,
    val warehouses: List<com.acme.b2b.domain.sellfox.SellfoxWarehouseScope>,
)
