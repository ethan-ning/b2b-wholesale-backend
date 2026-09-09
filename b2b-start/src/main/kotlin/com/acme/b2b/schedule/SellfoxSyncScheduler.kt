package com.acme.b2b.schedule

import com.acme.b2b.application.sellfox.SellfoxSyncService
import com.acme.b2b.domain.sellfox.TriggerSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The cron side of the Sellfox sync: two cadences over the one scope, hourly stock and a
 * nightly full run, which regroups as one of its steps.
 *
 * Lives in the start module because it drives the application layer, and `checkLayering`
 * forbids an adapter module from depending on that. A scheduler is a trigger like a
 * controller; the composition root is where non-HTTP triggers belong.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "sellfox.schedule", name = ["enabled"], havingValue = "true")
class SellfoxSyncScheduler(private val sync: SellfoxSyncService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${sellfox.schedule.inventory-cron}", zone = "\${sellfox.schedule.zone}")
    fun refreshStock() = guard("inventory") { sync.syncInventory(TriggerSource.SCHEDULED) }

    @Scheduled(cron = "\${sellfox.schedule.full-cron}", zone = "\${sellfox.schedule.zone}")
    fun fullSync() = guard("full") { sync.syncFull(TriggerSource.SCHEDULED) }

    /**
     * An unhandled error in a scheduled method can silence the schedule entirely, and the
     * run record already holds the failure in a form an admin can read. Logged at warn
     * rather than error because the commonest case is a fresh install with no scope set,
     * where the hourly job has nothing to do and should say so quietly.
     */
    private fun guard(label: String, work: () -> Unit) {
        try {
            work()
        } catch (ex: Exception) {
            log.warn("Scheduled Sellfox {} sync did not run: {}", label, ex.message)
        }
    }
}
