package com.acme.b2b.domain.inventory

import com.acme.b2b.types.SkuCode
import java.time.Instant

/**
 * Anti-corruption layer for the ERP that owns stock. The domain states what it needs —
 * levels per SKU — and infrastructure adapts Sellfox's payload to it, so a change to
 * their API stops at the adapter.
 */
interface StockSyncPort {
    fun fetchLevels(since: Instant?): List<StockSnapshot>
}

/** One SKU's stock as the ERP reports it, already translated out of their vocabulary. */
data class StockSnapshot(
    val sku: SkuCode,
    val warehouseCode: String,
    val available: Int,
    val incoming: Int,
    val observedAt: Instant,
)
