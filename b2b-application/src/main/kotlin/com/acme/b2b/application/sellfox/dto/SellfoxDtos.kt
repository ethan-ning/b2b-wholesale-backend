package com.acme.b2b.application.sellfox.dto

import com.acme.b2b.domain.sellfox.SellfoxCategoryScope
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.SellfoxWarehouseScope
import java.time.Instant

/**
 * What the admin screen is shown, as its own shape.
 *
 * The run entity is not serialised directly: it carries the transitions a run moves
 * through, and a field added for the sync's own use would silently become part of the API.
 */
data class SyncRunDTO(
    val id: Long?,
    val mode: String,
    val trigger: String,
    val status: String,
    val triggeredBy: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val recordsRead: Int,
    val recordsWritten: Int,
    val recordsSkipped: Int,
    val errorMessage: String?,
    val summary: String?,
)

data class SyncHistoryDTO(
    val runs: List<SyncRunDTO>,
    /** Whether a run is in flight, so the trigger button can disable itself. */
    val running: Boolean,
)

data class SellfoxScopeDTO(
    val categories: List<SellfoxCategoryScope>,
    val warehouses: List<SellfoxWarehouseScope>,
)

fun SellfoxSyncRun.toDto() = SyncRunDTO(
    id = id,
    mode = mode.name,
    trigger = trigger.name,
    status = status.name,
    triggeredBy = triggeredBy,
    startedAt = startedAt,
    finishedAt = finishedAt,
    recordsRead = recordsRead,
    recordsWritten = recordsWritten,
    recordsSkipped = recordsSkipped,
    errorMessage = errorMessage,
    summary = summary,
)
