package com.acme.b2b.domain.sellfox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SellfoxSyncRunTest {

    private val start = Instant.parse("2026-09-09T02:15:00Z")
    private val end = Instant.parse("2026-09-09T02:17:30Z")

    private fun started() =
        SellfoxSyncRun.started(SellfoxJob.CATALOG, TriggerSource.SCHEDULED, null, start)

    @Test
    fun `a run begins recorded rather than reported at the end`() {
        val run = started()

        assertEquals(RunStatus.RUNNING, run.status)
        assertNull(run.finishedAt)
        // "No row" would otherwise mean both "never ran" and "crashed", which are the two
        // cases an admin most needs to tell apart.
        assertEquals(start, run.startedAt)
    }

    @Test
    fun `a failure keeps the counts of the work that did happen`() {
        val counts = SyncCounts().apply { read(4000); wrote(120); skipped(30) }

        val failed = started().failed(end, counts, "Sellfox rate limit")

        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(4000, failed.recordsRead)
        assertEquals(120, failed.recordsWritten)
        // Reporting zero would make a partial run look like a total loss.
        assertEquals(30, failed.recordsSkipped)
        assertEquals(end, failed.finishedAt)
    }

    @Test
    fun `a failure with no message still says something`() {
        val failed = started().failed(end, SyncCounts(), null)

        assertNotNull(failed.errorMessage)
        assertTrue(failed.errorMessage!!.isNotBlank())
    }

    @Test
    fun `an enormous error message is truncated`() {
        val failed = started().failed(end, SyncCounts(), "x".repeat(10_000))

        assertEquals(2000, failed.errorMessage!!.length)
    }

    @Test
    fun `a manual run records who asked for it`() {
        val run = SellfoxSyncRun.started(
            SellfoxJob.INVENTORY, TriggerSource.MANUAL, "admin@example.com", start,
        )

        assertEquals(TriggerSource.MANUAL, run.trigger)
        assertEquals("admin@example.com", run.triggeredBy)
    }

    @Test
    fun `success carries the summary the admin reads`() {
        val counts = SyncCounts().apply { read(6422); wrote(72) }

        val ok = started().succeeded(end, counts, "72 products across 2 categories")

        assertEquals(RunStatus.SUCCESS, ok.status)
        assertEquals("72 products across 2 categories", ok.summary)
        assertNull(ok.errorMessage)
    }
}
