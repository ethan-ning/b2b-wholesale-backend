package com.acme.b2b.domain.sellfox

import java.time.Instant

interface SellfoxSyncRunRepository {
    fun save(run: SellfoxSyncRun): SellfoxSyncRun
    fun recent(limit: Int): List<SellfoxSyncRun>

    /**
     * Records the start of a run, or returns null because one is already going.
     *
     * One call rather than ask-then-insert. Between those two steps a second caller can
     * ask and get the same answer, and two instances behind a load balancer will — so the
     * exclusion has to be settled where the row is written, not before it.
     */
    fun startExclusively(run: SellfoxSyncRun): SellfoxSyncRun?

    /** True while a run is still RUNNING. A courtesy check; [startExclusively] is the guard. */
    fun isRunning(): Boolean

    /**
     * Closes runs left RUNNING by a process that died, and only those: a run that started
     * before [startedBefore] cannot still be going, because no sync takes that long.
     *
     * Without it a crash wedges the job permanently — the row never finishes, so every
     * later run is refused as concurrent. Bounded by age rather than closing everything,
     * because a second instance starting up must not declare a live run on another
     * instance dead and let a duplicate through.
     *
     * Returns how many were closed.
     */
    fun failInterrupted(reason: String, at: Instant, startedBefore: Instant): Int
}
