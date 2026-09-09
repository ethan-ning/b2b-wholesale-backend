package com.acme.b2b.schedule

import com.acme.b2b.application.sellfox.SellfoxCatalogSyncService
import com.acme.b2b.application.sellfox.SellfoxInventorySyncService
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
 * Both schedules are properties, not constants: how often stock should be re-read is an
 * operational decision that changes without a deploy.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "sellfox.schedule", name = ["enabled"], havingValue = "true")
class SellfoxSyncScheduler(
    private val catalog: SellfoxCatalogSyncService,
    private val inventory: SellfoxInventorySyncService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Stock, often. Reads only the selected warehouses, so it costs a handful of pages.
     */
    @Scheduled(cron = "\${sellfox.schedule.inventory-cron}", zone = "\${sellfox.schedule.zone}")
    fun syncInventory() = guard("inventory") {
        inventory.sync(TriggerSource.SCHEDULED)
    }

    /**
     * The catalog, nightly. It has to page every commodity Sellfox holds — the endpoint
     * takes no category filter — so it is deliberately infrequent and scheduled for a
     * quiet hour.
     */
    @Scheduled(cron = "\${sellfox.schedule.catalog-cron}", zone = "\${sellfox.schedule.zone}")
    fun syncCatalog() = guard("catalog") {
        catalog.sync(TriggerSource.SCHEDULED)
    }

    /**
     * A scheduled method that throws is logged by Spring and then simply not retried;
     * worse, an unhandled error here can silence the schedule. The run record already
     * holds the failure in a form an admin can read, so this only has to stop the
     * exception escaping.
     */
    private fun guard(label: String, work: () -> Unit) {
        try {
            work()
        } catch (ex: Exception) {
            log.error("Scheduled Sellfox {} sync failed; see sellfox_sync_run for the record", label, ex)
        }
    }
}
