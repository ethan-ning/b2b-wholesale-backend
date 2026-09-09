package com.acme.b2b.application.sellfox

import com.acme.b2b.application.sellfox.dto.SellfoxScopeDTO
import com.acme.b2b.application.sellfox.dto.SyncRunDTO
import com.acme.b2b.application.sellfox.dto.toDto
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.sellfox.SellfoxScopeRepository
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

    fun scope() = SellfoxScopeDTO(
        categories = scope.categories(),
        warehouses = scope.warehouses(),
    )

    fun history(limit: Int): List<SyncRunDTO> =
        runs.recent(limit.coerceIn(1, MAX_HISTORY)).map { it.toDto() }

    /** True while a run is in flight, so the trigger button can say so before it 409s. */
    fun running(): Boolean = runs.isRunning()

    /**
     * Sets the whole scope in one call, because it is one decision — which product lines
     * this site carries and which warehouses can ship them. Saved as a unit so it cannot
     * sit half-changed between two requests, with a run firing in the gap.
     *
     * Both halves are required here rather than at sync time: the scope is meant to be
     * set once and left, so a half-set one is a mistake to catch on the way in.
     */
    @Transactional
    fun setScope(cids: Set<String>, warehouseIds: Set<Long>) {
        if (cids.isEmpty() || warehouseIds.isEmpty()) {
            throw UseCaseViolation("The scope needs at least one category and one warehouse")
        }
        scope.selectCategories(cids)
        scope.selectWarehouses(warehouseIds)
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}

