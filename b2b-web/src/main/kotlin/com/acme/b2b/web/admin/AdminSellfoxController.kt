package com.acme.b2b.web.admin

import com.acme.b2b.application.sellfox.SellfoxAdminService
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.application.sellfox.SellfoxCatalogSyncService
import com.acme.b2b.application.sellfox.SellfoxInventorySyncService
import com.acme.b2b.application.sellfox.SellfoxScopeView
import com.acme.b2b.domain.sellfox.SellfoxJob
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
 * request open. A catalog sync pages every commodity Sellfox holds and takes about a
 * minute; a browser waiting on that has usually given up before it finishes, and the run
 * history is where the outcome belongs anyway.
 */
@RestController
@RequestMapping("/api/admin/sellfox")
class AdminSellfoxController(
    private val admin: SellfoxAdminService,
    private val catalog: SellfoxCatalogSyncService,
    private val inventory: SellfoxInventorySyncService,
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
    fun runs(
        @RequestParam(required = false) job: String?,
        @RequestParam(defaultValue = "25") limit: Int,
    ): SyncHistoryResponse = SyncHistoryResponse(
        runs = admin.history(job?.let { SellfoxJob.valueOf(it.uppercase()) }, limit),
        running = admin.running().mapKeys { it.key.name },
    )

    /**
     * Starts a run and returns its record in RUNNING. The client polls `/runs` for the
     * outcome — the same place a scheduled run reports it, so there is one way to read
     * what happened rather than two.
     */
    @PostMapping("/runs/{job}")
    fun trigger(@PathVariable job: String): SellfoxSyncRun {
        val which = SellfoxJob.valueOf(job.uppercase())
        val by = currentAdminEmail()

        // Checked here, on the request thread, so a second click gets a 409 it can see.
        // The runner checks again on its own thread, but by then the exception has
        // nowhere to go — an admin would be told the run started when it had not.
        if (admin.running()[which] == true) {
            throw UseCaseViolation("A ${which.name.lowercase()} sync is already running")
        }

        val started = when (which) {
            SellfoxJob.CATALOG -> CompletableFuture.supplyAsync { catalog.sync(TriggerSource.MANUAL, by) }
            SellfoxJob.INVENTORY -> CompletableFuture.supplyAsync { inventory.sync(TriggerSource.MANUAL, by) }
        }

        // A brief wait so the response carries the run's own record rather than the
        // previous one. A failure past this point is not lost — the runner writes it to
        // the history, which is where a scheduled run reports too.
        return started.completeOnTimeout(null, HANDOFF_MILLIS, TimeUnit.MILLISECONDS)
            .exceptionally { null }
            .join()
            ?: admin.history(which, 1).firstOrNull()
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
    /** Job name -> whether one is in flight, so the trigger button can disable itself. */
    val running: Map<String, Boolean>,
)
