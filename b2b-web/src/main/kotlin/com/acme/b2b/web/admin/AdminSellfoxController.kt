package com.acme.b2b.web.admin

import com.acme.b2b.application.sellfox.SellfoxAdminService
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.application.sellfox.SellfoxSyncService
import com.acme.b2b.application.sellfox.dto.SellfoxScopeDTO
import com.acme.b2b.application.sellfox.dto.SyncHistoryDTO
import com.acme.b2b.application.sellfox.dto.SyncRunDTO
import com.acme.b2b.application.sellfox.dto.toDto
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
    fun scope(): SellfoxScopeDTO = admin.scope()

    /**
     * Replaces the whole scope and immediately starts a full sync.
     *
     * The sync is not optional. Narrowing the scope leaves products in the catalog that
     * are no longer meant to be there, and the run is what deactivates them — saving
     * without it would leave the site selling something the admin had just removed.
     */
    @PutMapping("/scope")
    fun setScope(@RequestBody body: ScopeRequest): SyncRunDTO {
        requireNotRunning()
        // Read here, not inside the lambda: the security context is a thread-local, and
        // the run executes on a pool thread that has none. Read there it comes back null,
        // and the history loses who changed the scope — the one run where that matters.
        val by = currentAdminEmail()
        admin.setScope(body.cids, body.warehouseIds)
        return start(TriggerSource.SCOPE_CHANGE) { sync.syncFull(it, by) }
    }

    @GetMapping("/runs")
    fun runs(@RequestParam(defaultValue = "25") limit: Int): SyncHistoryDTO =
        SyncHistoryDTO(runs = admin.history(limit), running = admin.running())

    /** `mode=inventory` refreshes stock only. The default is a full run. */
    @PostMapping("/runs")
    fun trigger(@RequestParam(required = false) mode: String?): SyncRunDTO {
        val by = currentAdminEmail()
        requireNotRunning()
        sync.requireScopeChosen()

        return start(TriggerSource.MANUAL) {
            if (mode?.lowercase() == "inventory") sync.syncInventory(it, by) else sync.syncFull(it, by)
        }
    }

    /**
     * Refuses a second concurrent run, on the request thread.
     *
     * The sync checks again on its own thread, but an exception raised there has nowhere
     * to go: it would be swallowed and the admin told a run had started when it had not.
     */
    private fun requireNotRunning() {
        if (admin.running()) throw UseCaseViolation("A sync is already running")
    }

    /** Dispatches the run, waiting just long enough that the response carries this run. */
    private fun start(
        trigger: TriggerSource,
        run: (TriggerSource) -> SellfoxSyncRun,
    ): SyncRunDTO =
        CompletableFuture.supplyAsync { run(trigger).toDto() }
            .completeOnTimeout(null, HANDOFF_MILLIS, TimeUnit.MILLISECONDS)
            .exceptionally { null }
            .join()
            ?: admin.history(1).firstOrNull()
            ?: throw IllegalStateException("Sync did not start")

    private fun currentAdminEmail(): String? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
            ?.token?.getClaim<String>("email")

    private companion object {
        const val HANDOFF_MILLIS = 800L
    }
}

data class ScopeRequest(
    val cids: Set<String> = emptySet(),
    val warehouseIds: Set<Long> = emptySet(),
)
