package com.acme.b2b.application.sellfox

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.SellfoxSyncRunRepository
import com.acme.b2b.domain.sellfox.SyncCounts
import com.acme.b2b.domain.sellfox.TriggerSource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Wraps the sync in its run record — history, concurrency and failure handling in one
 * place, separate from what the sync actually does.
 *
 * The run row is committed before the work starts and again after it ends, in their own
 * transactions. Sharing the job's transaction would roll the record back with the
 * failure it exists to describe — the case where a record matters most.
 */
@Component
class SyncRunner(
    private val runs: SellfoxSyncRunRepository,
    private val clock: Clock,
) {

    fun run(
        trigger: TriggerSource,
        triggeredBy: String?,
        work: (SyncCounts) -> String,
    ): SellfoxSyncRun {
        if (runs.isRunning()) {
            throw UseCaseViolation("A sync is already running")
        }

        val started = begin(trigger, triggeredBy)
        val counts = SyncCounts()

        return try {
            val summary = work(counts)
            finish(started.succeeded(clock.instant(), counts, summary))
        } catch (ex: Exception) {
            finish(started.failed(clock.instant(), counts, describe(ex)))
            throw ex
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun begin(trigger: TriggerSource, triggeredBy: String?): SellfoxSyncRun =
        runs.save(SellfoxSyncRun.started(trigger, triggeredBy, clock.instant()))

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finish(run: SellfoxSyncRun): SellfoxSyncRun = runs.save(run)

    /**
     * The message plus the exception type. "null" on its own — which is what a bare
     * message gives you for an NPE — tells an admin nothing about what broke.
     */
    private fun describe(ex: Exception): String =
        "${ex::class.simpleName}: ${ex.message ?: "no message"}"
}
