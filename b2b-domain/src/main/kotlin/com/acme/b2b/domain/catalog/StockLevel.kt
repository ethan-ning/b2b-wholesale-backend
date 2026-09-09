package com.acme.b2b.domain.catalog

import java.time.Instant

/**
 * Stock for one SKU. Sellfox is the system of record; this is a read replica, so
 * [lastSyncedAt] is part of the model — dealers are shown how fresh the number is.
 *
 * Reserved and defective stock are tracked upstream but deliberately absent here:
 * neither is shown to dealers, and a field nobody reads is a field that drifts.
 */
data class StockLevel(
    val available: Int,
    val incoming: Int,
    val lastSyncedAt: Instant,
) {
    init {
        require(available >= 0) { "Available stock must not be negative" }
        require(incoming >= 0) { "Incoming stock must not be negative" }
    }

    val isOutOfStock: Boolean get() = available == 0
    val isLow: Boolean get() = available in 1 until LOW_STOCK_THRESHOLD
    val hasIncoming: Boolean get() = incoming > 0

    companion object {
        const val LOW_STOCK_THRESHOLD = 5
        fun empty(at: Instant): StockLevel = StockLevel(0, 0, at)
    }
}
