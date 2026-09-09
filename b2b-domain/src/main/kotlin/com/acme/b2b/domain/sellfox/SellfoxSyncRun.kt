package com.acme.b2b.domain.sellfox

import java.time.Instant

enum class TriggerSource { SCHEDULED, MANUAL }

enum class RunStatus { RUNNING, SUCCESS, FAILED }

/**
 * One execution of the sync — including the ones that failed, and the ones still going.
 *
 * A run is written before the work starts, not after it finishes. A run that dies
 * halfway would otherwise leave no trace at all, and "no row" would mean both "never
 * ran" and "crashed", which are the two cases an admin most needs to tell apart.
 */
data class SellfoxSyncRun(
    val id: Long?,
    val trigger: TriggerSource,
    val status: RunStatus,
    /** The admin who pressed the button; null for scheduled runs. */
    val triggeredBy: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val recordsRead: Int = 0,
    val recordsWritten: Int = 0,
    val recordsSkipped: Int = 0,
    val errorMessage: String? = null,
    val summary: String? = null,
) {
    companion object {
        /** Long enough to be diagnostic, short enough that a stack trace cannot fill a page. */
        private const val MAX_ERROR_LENGTH = 2000

        fun started(trigger: TriggerSource, triggeredBy: String?, at: Instant) =
            SellfoxSyncRun(
                id = null,
                trigger = trigger,
                status = RunStatus.RUNNING,
                triggeredBy = triggeredBy,
                startedAt = at,
                finishedAt = null,
            )
    }

    fun succeeded(at: Instant, counts: SyncCounts, summary: String) = copy(
        status = RunStatus.SUCCESS,
        finishedAt = at,
        recordsRead = counts.read,
        recordsWritten = counts.written,
        recordsSkipped = counts.skipped,
        summary = summary,
    )

    /**
     * Keeps the counts. A run that failed on page 40 of 60 did real work, and hiding that
     * makes the failure look total when it was partial.
     */
    fun failed(at: Instant, counts: SyncCounts, error: String?) = copy(
        status = RunStatus.FAILED,
        finishedAt = at,
        recordsRead = counts.read,
        recordsWritten = counts.written,
        recordsSkipped = counts.skipped,
        errorMessage = (error ?: "Unknown error").take(MAX_ERROR_LENGTH),
    )

}

/** Mutable tally carried through a run so a failure can still report what got done. */
class SyncCounts {
    var read: Int = 0; private set
    var written: Int = 0; private set
    var skipped: Int = 0; private set

    fun read(n: Int) { read += n }
    fun wrote(n: Int = 1) { written += n }
    fun skipped(n: Int = 1) { skipped += n }
}

interface SellfoxSyncRunRepository {
    fun save(run: SellfoxSyncRun): SellfoxSyncRun
    fun recent(limit: Int): List<SellfoxSyncRun>
    /** True while a run is still RUNNING — used to refuse a concurrent start. */
    fun isRunning(): Boolean

    /**
     * Closes runs left RUNNING by a process that died. Without this a crash wedges the
     * job permanently: the row never finishes, so every later run is refused as
     * concurrent, and the only symptom is a button that stays disabled.
     *
     * Returns how many were closed.
     */
    fun failInterrupted(reason: String, at: java.time.Instant): Int
}
