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

    @PutMapping("/scope/categories/{cid}")
    fun selectCategory(@PathVariable cid: String, @RequestBody body: SelectionRequest) =
        admin.selectCategory(cid, body.selected)

    @PutMapping("/scope/warehouses/{warehouseId}")
    fun selectWarehouse(@PathVariable warehouseId: Long, @RequestBody body: SelectionRequest) =
        admin.selectWarehouse(warehouseId, body.selected)

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

        // Checked here, on the request thread, so a second click gets a 409 it can see.
        // The runner checks again on its own thread, but by then the exception has
        // nowhere to go — an admin would be told the run started when it had not.
        if (admin.running()) throw UseCaseViolation("A sync is already running")

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

data class SelectionRequest(val selected: Boolean)

data class SyncHistoryResponse(
    val runs: List<SellfoxSyncRun>,
    /** Whether a run is in flight, so the trigger button can disable itself. */
    val running: Boolean,
)
