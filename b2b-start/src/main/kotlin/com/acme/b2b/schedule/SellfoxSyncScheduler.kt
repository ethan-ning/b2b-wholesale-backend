package com.acme.b2b.schedule

import com.acme.b2b.application.sellfox.SellfoxSyncService
import com.acme.b2b.domain.sellfox.TriggerSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The cron side of the Sellfox sync: two cadences over the one scope.
 *
 * Regrouping is not among them. Its inputs only change when a full run brings in new
 * SKUs, and that run regroups within itself — so a separate schedule would spend its
 * wake-ups confirming an answer nothing had disturbed. It is worth running when the
 * grouping rules change, which is a deploy, not an hour of the day, so it is triggered
 * by hand.
 *
 * Lives in the start module rather than infrastructure because it drives the application
 * layer, and the dependency rule (enforced by `checkLayering`) forbids an adapter module
 * from depending on it. A scheduler is a trigger, exactly like a controller — the
 * composition root is where triggers that are not HTTP belong.
 *
 * Both schedules are properties: how fresh stock needs to be is an operational decision
 * that should not need a deploy.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "sellfox.schedule", name = ["enabled"], havingValue = "true")
class SellfoxSyncScheduler(private val sync: SellfoxSyncService) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Stock, hourly. Reads only the selected warehouses, so it costs a handful of pages —
     * and stock is the part that actually moves between catalog changes.
     */
    @Scheduled(cron = "\${sellfox.schedule.inventory-cron}", zone = "\${sellfox.schedule.zone}")
    fun refreshStock() = guard("inventory") { sync.syncInventory(TriggerSource.SCHEDULED) }

    /**
     * The whole catalog, daily. Pages every commodity Sellfox holds — the endpoint takes
     * no category filter — and deactivates anything that has left the scope. Too
     * expensive to run hourly, which is exactly why the stock pass exists.
     */
    @Scheduled(cron = "\${sellfox.schedule.full-cron}", zone = "\${sellfox.schedule.zone}")
    fun fullSync() = guard("full") { sync.syncFull(TriggerSource.SCHEDULED) }

    /**
     * A scheduled method that throws is logged by Spring and then simply not retried, and
     * an unhandled error can silence the schedule entirely. The run record already holds
     * the failure in a form an admin can read, so this only has to stop it escaping.
     *
     * That includes the refusal when no scope is set: on a fresh install the hourly job
     * has nothing to do, and should say so quietly rather than raise an alarm every hour.
     */
    private fun guard(label: String, work: () -> Unit) {
        try {
            work()
        } catch (ex: Exception) {
            log.warn("Scheduled Sellfox {} sync did not run: {}", label, ex.message)
        }
    }
}
