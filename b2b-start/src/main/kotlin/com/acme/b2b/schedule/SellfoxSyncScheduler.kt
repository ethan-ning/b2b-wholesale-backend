package com.acme.b2b.schedule

import com.acme.b2b.application.sellfox.SellfoxSyncService
import com.acme.b2b.domain.sellfox.TriggerSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The cron side of the Sellfox sync.
 *
 * Lives in the start module rather than infrastructure because it drives the application
 * layer, and the dependency rule (enforced by `checkLayering`) forbids an adapter module
 * from depending on it. A scheduler is a trigger, exactly like a controller — the
 * composition root is where triggers that are not HTTP belong.
 *
 * The schedule is a property, not a constant: how fresh stock needs to be is an
 * operational decision that changes without a deploy.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "sellfox.schedule", name = ["enabled"], havingValue = "true")
class SellfoxSyncScheduler(private val sync: SellfoxSyncService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${sellfox.schedule.cron}", zone = "\${sellfox.schedule.zone}")
    fun run() {
        try {
            sync.sync(TriggerSource.SCHEDULED)
        } catch (ex: Exception) {
            // A scheduled method that throws is logged by Spring and simply not retried,
            // and an unhandled error here can silence the schedule entirely. The run
            // record already holds the failure in a form an admin can read, so this only
            // has to stop the exception escaping.
            log.error("Scheduled Sellfox sync failed; see sellfox_sync_run for the record", ex)
        }
    }
}
