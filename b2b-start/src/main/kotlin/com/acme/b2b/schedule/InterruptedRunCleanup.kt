package com.acme.b2b.schedule

import com.acme.b2b.domain.sellfox.SellfoxSyncRunRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import java.time.Clock
import java.time.Duration

/**
 * Closes sync runs left RUNNING by a process that died.
 *
 * A run is written before its work starts, so a crash — or a kill during development —
 * leaves a row that never finishes. That row then refuses every later run as concurrent,
 * and the only symptom is a button that stays disabled with no explanation. Marking them
 * failed at startup is the recovery, and it keeps the record honest: the run really did
 * not complete.
 *
 * Bounded by age rather than closing everything RUNNING. With one instance those are the
 * same set, but behind a load balancer a starting instance would otherwise declare a live
 * run on another instance dead — and the database's one-run-at-a-time guard would then let
 * a duplicate through, which is the failure this is supposed to prevent.
 *
 * [staleAfter] only has to exceed the longest legitimate run; a full sync pages the whole
 * catalogue in a couple of minutes. A crash therefore self-heals, just not instantly.
 */
@Component
class InterruptedRunCleanup(
    private val runs: SellfoxSyncRunRepository,
    private val clock: Clock,
    @Value("\${sellfox.run-stale-after:PT15M}") private val staleAfter: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun closeInterruptedRuns() {
        val now = clock.instant()
        val closed = runs.failInterrupted(
            "Interrupted — the application stopped mid-run",
            now,
            startedBefore = now.minus(staleAfter),
        )
        if (closed > 0) log.warn("Closed {} Sellfox sync run(s) left running by a previous process", closed)
    }
}
