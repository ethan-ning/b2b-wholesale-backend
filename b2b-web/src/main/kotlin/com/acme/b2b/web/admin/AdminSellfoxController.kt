package com.acme.b2b.web.admin

import com.acme.b2b.application.sellfox.SellfoxAdminService
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.application.sellfox.SellfoxScopeView
import com.acme.b2b.application.sellfox.SellfoxSyncService
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.TriggerSource
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Sellfox screen: what is in scope, what ran, and the button that runs it now.
 *
 * A manual trigger returns immediately with the run record rather than holding the
 * request open. A run pages every commodity Sellfox holds and takes a couple of minutes;
 * a browser waiting on that has usually given up before it finishes, and the run history
 * is where the outcome belongs anyway.
 */
@RestController
@RequestMapping("/api/admin/sellfox")
class AdminSellfoxController(
    private val admin: SellfoxAdminService,
    private val sync: SellfoxSyncService,
) {

    @GetMapping("/scope")
    fun scope(): SellfoxScopeView = admin.scope()

    /** The complete selection, not a toggle — so clearing is one call, not ninety. */
    @PutMapping("/scope/categories")
    fun selectCategories(@RequestBody body: CategorySelection) = admin.selectCategories(body.cids)

    @PutMapping("/scope/warehouses")
    fun selectWarehouses(@RequestBody body: WarehouseSelection) = admin.selectWarehouses(body.warehouseIds)

    @GetMapping("/runs")
    fun runs(@RequestParam(defaultValue = "25") limit: Int): SyncHistoryResponse =
        SyncHistoryResponse(runs = admin.history(limit), running = admin.running())

    /**
     * Starts a run and returns its record in RUNNING. The client polls `/runs` for the
     * outcome — the same place a scheduled run reports it, so there is one way to read
     * what happened rather than two.
     */
    @PostMapping("/runs")
    fun trigger(): SellfoxSyncRun {
        val by = currentAdminEmail()

        // Both checked here, on the request thread. The sync checks again on its own
        // thread, but an exception there has nowhere to go: it would be swallowed and the
        // admin told a run had started when it had not — or worse, handed the previous
        // run's record as if it were this one's.
        if (admin.running()) throw UseCaseViolation("A sync is already running")
        sync.requireScopeChosen()

        val started = CompletableFuture.supplyAsync { sync.sync(TriggerSource.MANUAL, by) }

        // A brief wait so the response carries the run's own record rather than the
        // previous one. A failure past this point is not lost — the runner writes it to
        // the history, which is where a scheduled run reports too.
        return started.completeOnTimeout(null, HANDOFF_MILLIS, TimeUnit.MILLISECONDS)
            .exceptionally { null }
            .join()
            ?: admin.history(1).firstOrNull()
            ?: throw IllegalStateException("Sync did not start")
    }

    private fun currentAdminEmail(): String? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
            ?.token?.getClaim<String>("email")

    private companion object {
        const val HANDOFF_MILLIS = 800L
    }
}

data class CategorySelection(val cids: Set<String> = emptySet())

data class WarehouseSelection(val warehouseIds: Set<Long> = emptySet())

data class SyncHistoryResponse(
    val runs: List<SellfoxSyncRun>,
    /** Whether a run is in flight, so the trigger button can disable itself. */
    val running: Boolean,
)
