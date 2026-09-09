package com.acme.b2b.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "sellfox_category")
class SellfoxCategoryDO(
    @Id
    var cid: String = "",

    @Column(name = "full_cid", nullable = false)
    var fullCid: String = "",

    @Column(name = "full_name", nullable = false)
    var fullName: String = "",

    @Column(name = "commodity_count", nullable = false)
    var commodityCount: Int = 0,

    @Column(nullable = false)
    var selected: Boolean = false,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "sellfox_warehouse")
class SellfoxWarehouseDO(
    @Id
    @Column(name = "warehouse_id")
    var warehouseId: Long = 0,

    @Column(nullable = false)
    var name: String = "",

    var type: Int? = null,

    @Column(nullable = false)
    var selected: Boolean = false,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "sellfox_sku_link")
class SellfoxSkuLinkDO(
    @Id
    @Column(name = "sellfox_sku")
    var sellfoxSku: String = "",

    @Column(name = "commodity_id", nullable = false)
    var commodityId: String = "",

    @Column(name = "full_cid", nullable = false)
    var fullCid: String = "",

    @Column(name = "declared_spu")
    var declaredSpu: String? = null,

    @Column(name = "base_sellfox_sku")
    var baseSellfoxSku: String? = null,

    @Column(name = "base_quantity")
    var baseQuantity: Int? = null,

    @Column(name = "commodity_name")
    var commodityName: String? = null,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "sellfox_sync_run")
class SellfoxSyncRunDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var mode: String = "",

    @Column(name = "trigger_source", nullable = false)
    var triggerSource: String = "",

    @Column(nullable = false)
    var status: String = "",

    @Column(name = "triggered_by")
    var triggeredBy: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.EPOCH,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "records_read", nullable = false)
    var recordsRead: Int = 0,

    @Column(name = "records_written", nullable = false)
    var recordsWritten: Int = 0,

    @Column(name = "records_skipped", nullable = false)
    var recordsSkipped: Int = 0,

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null,

    @Column(columnDefinition = "text")
    var summary: String? = null,
)
