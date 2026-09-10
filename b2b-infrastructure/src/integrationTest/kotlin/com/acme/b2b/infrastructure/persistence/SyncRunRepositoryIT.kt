package com.acme.b2b.infrastructure.persistence

import com.acme.b2b.domain.sellfox.RunStatus
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.SyncMode
import com.acme.b2b.domain.sellfox.TriggerSource
import com.acme.b2b.infrastructure.PostgresTest
import com.acme.b2b.infrastructure.persistence.repository.SellfoxSyncRunRepositoryImpl
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * That only one sync can be in flight, decided by Postgres rather than by asking first.
 *
 * A fake cannot show this: the interesting case is two callers racing, and the partial
 * unique index is what settles it.
 */
@Import(SellfoxSyncRunRepositoryImpl::class)
class SyncRunRepositoryIT : PostgresTest() {

    @Autowired private lateinit var runs: SellfoxSyncRunRepositoryImpl
    @Autowired private lateinit var em: EntityManager

    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private fun starting(mode: SyncMode = SyncMode.FULL, at: Instant = now) =
        SellfoxSyncRun.started(mode, TriggerSource.SCHEDULED, null, at)

    private fun clear() {
        em.createNativeQuery("DELETE FROM sellfox_sync_run").executeUpdate()
        em.flush(); em.clear()
    }

    @Test
    fun `the first caller starts a run`() {
        clear()

        val started = runs.startExclusively(starting())

        assertNotNull(started)
        assertEquals(RunStatus.RUNNING, started.status)
        assertNotNull(started.id)
    }

    @Test
    fun `the second is turned away while the first is still going`() {
        clear()
        runs.startExclusively(starting(SyncMode.FULL))

        val second = runs.startExclusively(starting(SyncMode.INVENTORY))

        assertNull(second)
        em.flush(); em.clear()
        assertEquals(1, runs.recent(10).size)
    }

    @Test
    fun `a new run may start once the last one has finished`() {
        clear()
        val first = runs.startExclusively(starting())!!
        runs.save(first.succeeded(now, com.acme.b2b.domain.sellfox.SyncCounts(), "done"))
        em.flush(); em.clear()

        assertNotNull(runs.startExclusively(starting(at = now.plusSeconds(60))))
    }

    @Test
    fun `finished runs do not compete for the slot`() {
        clear()
        // The index covers RUNNING rows only, so history can grow without bound.
        repeat(3) {
            val run = runs.startExclusively(starting(at = now.plusSeconds(it.toLong())))!!
            runs.save(run.failed(now, com.acme.b2b.domain.sellfox.SyncCounts(), "nope"))
            em.flush(); em.clear()
        }

        assertNotNull(runs.startExclusively(starting(at = now.plusSeconds(99))))
        assertEquals(4, runs.recent(10).size)
    }

    // ─── Interrupted runs ────────────────────────────────────────────────

    @Test
    fun `a run old enough to be dead is closed, and frees the slot`() {
        clear()
        runs.startExclusively(starting(at = now.minus(Duration.ofHours(2))))
        em.flush(); em.clear()

        val closed = runs.failInterrupted("Interrupted", now, startedBefore = now.minus(Duration.ofMinutes(15)))

        assertEquals(1, closed)
        em.flush(); em.clear()
        assertEquals(RunStatus.FAILED, runs.recent(1).single().status)
        assertNotNull(runs.startExclusively(starting()))
    }

    @Test
    fun `a run that started moments ago is left alone`() {
        clear()
        runs.startExclusively(starting(at = now.minus(Duration.ofMinutes(1))))
        em.flush(); em.clear()

        // An instance starting up must not declare another instance's live run dead —
        // doing so would free the slot and let a second sync run alongside the first.
        val closed = runs.failInterrupted("Interrupted", now, startedBefore = now.minus(Duration.ofMinutes(15)))

        assertEquals(0, closed)
        em.flush(); em.clear()
        assertEquals(RunStatus.RUNNING, runs.recent(1).single().status)
        assertNull(runs.startExclusively(starting()))
    }
}
