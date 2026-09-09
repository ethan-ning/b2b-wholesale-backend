package com.acme.b2b.schedule

import com.acme.b2b.domain.sellfox.SellfoxSyncRunRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Closes sync runs left RUNNING by a process that died.
 *
 * A run is written before its work starts, so a crash — or a kill during development —
 * leaves a row that never finishes. That row then refuses every later run as concurrent,
 * and the only symptom is a button that stays disabled with no explanation. Marking them
 * failed at startup is the recovery, and it keeps the record honest: the run really did
 * not complete.
 *
 * Safe because a run cannot outlive the process that owns it — there is no handoff, so
 * anything still RUNNING when this instance starts belongs to an instance that is gone.
 */
@Component
class InterruptedRunCleanup(
    private val runs: SellfoxSyncRunRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun closeInterruptedRuns() {
        val closed = runs.failInterrupted("Interrupted — the application stopped mid-run", clock.instant())
        if (closed > 0) log.warn("Closed {} Sellfox sync run(s) left running by a previous process", closed)
    }
}
