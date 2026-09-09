package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.sellfox.*
import com.acme.b2b.infrastructure.persistence.entity.SellfoxCategoryDO
import com.acme.b2b.infrastructure.persistence.entity.SellfoxSkuLinkDO
import com.acme.b2b.infrastructure.persistence.entity.SellfoxSyncRunDO
import com.acme.b2b.infrastructure.persistence.entity.SellfoxWarehouseDO
import com.acme.b2b.infrastructure.persistence.jpa.SellfoxCategoryJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.SellfoxSkuLinkJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.SellfoxSyncRunJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.SellfoxWarehouseJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SellfoxScopeRepositoryImpl(
    private val categories: SellfoxCategoryJpaRepository,
    private val warehouses: SellfoxWarehouseJpaRepository,
) : SellfoxScopeRepository {

    override fun selectedCategoryIds(): Set<String> =
        categories.findBySelectedTrue().map { it.cid }.toSet()

    override fun selectedWarehouseIds(): List<Long> =
        warehouses.findBySelectedTrue().map { it.warehouseId }

    override fun categories(): List<SellfoxCategoryScope> =
        categories.findAll().sortedBy { it.fullName }.map { it.toDomain() }

    override fun warehouses(): List<SellfoxWarehouseScope> =
        warehouses.findAll().sortedBy { it.name }.map { it.toDomain() }

    /**
     * Upserts what a scan saw and leaves `selected` alone — a refresh must never quietly
     * change the scope, only the menu it is chosen from.
     */
    override fun refreshCategories(seen: List<SellfoxCategoryScope>, at: Instant) {
        val existing = categories.findAll().associateBy { it.cid }
        val rows = seen.map { scope ->
            (existing[scope.cid] ?: SellfoxCategoryDO(cid = scope.cid)).apply {
                fullCid = scope.fullCid
                fullName = scope.fullName
                commodityCount = scope.commodityCount
                lastSeenAt = at
            }
        }
        categories.saveAll(rows)
    }

    override fun refreshWarehouses(seen: List<SellfoxWarehouse>, at: Instant) {
        val existing = warehouses.findAll().associateBy { it.warehouseId }
        val rows = seen.map { warehouse ->
            (existing[warehouse.warehouseId] ?: SellfoxWarehouseDO(warehouseId = warehouse.warehouseId)).apply {
                name = warehouse.name
                type = warehouse.type
                lastSeenAt = at
            }
        }
        warehouses.saveAll(rows)
    }

    /**
     * Sets the selection to exactly these, clearing the rest. Unknown ids are refused
     * rather than ignored: silently dropping one would report a scope the admin did not
     * choose, and they would find out from an import that missed a product line.
     */
    override fun selectCategories(cids: Set<String>) {
        val rows = categories.findAll()
        val known = rows.map { it.cid }.toSet()
        (cids - known).takeIf { it.isNotEmpty() }?.let {
            throw NoSuchElementException("No such Sellfox category group: ${it.joinToString()}")
        }
        rows.forEach { it.selected = it.cid in cids }
        categories.saveAll(rows)
    }

    override fun selectWarehouses(warehouseIds: Set<Long>) {
        val rows = warehouses.findAll()
        val known = rows.map { it.warehouseId }.toSet()
        (warehouseIds - known).takeIf { it.isNotEmpty() }?.let {
            throw NoSuchElementException("No such Sellfox warehouse: ${it.joinToString()}")
        }
        rows.forEach { it.selected = it.warehouseId in warehouseIds }
        warehouses.saveAll(rows)
    }

    private fun SellfoxCategoryDO.toDomain() = SellfoxCategoryScope(
        cid, fullCid, fullName, commodityCount, selected, lastSeenAt,
    )

    private fun SellfoxWarehouseDO.toDomain() = SellfoxWarehouseScope(
        warehouseId, name, type, selected, lastSeenAt,
    )
}

@Repository
class SellfoxSkuLinkRepositoryImpl(
    private val jpa: SellfoxSkuLinkJpaRepository,
) : SellfoxSkuLinkRepository {

    override fun save(link: SellfoxSkuLink) {
        jpa.save(
            SellfoxSkuLinkDO(
                sellfoxSku = link.sellfoxSku,
                commodityId = link.commodityId,
                fullCid = link.fullCid,
                declaredSpu = link.declaredSpu,
                baseSellfoxSku = link.baseSellfoxSku,
                baseQuantity = link.baseQuantity,
                commodityName = link.commodityName,
                lastSeenAt = link.lastSeenAt,
            )
        )
    }

    override fun findAll(): List<SellfoxSkuLink> = jpa.findAll().map {
        SellfoxSkuLink(
            sellfoxSku = it.sellfoxSku,
            commodityId = it.commodityId,
            fullCid = it.fullCid,
            declaredSpu = it.declaredSpu,
            baseSellfoxSku = it.baseSellfoxSku,
            baseQuantity = it.baseQuantity,
            commodityName = it.commodityName.orEmpty(),
            lastSeenAt = it.lastSeenAt,
        )
    }

    override fun skusFor(fullCids: Set<String>): Set<String> =
        if (fullCids.isEmpty()) emptySet()
        else jpa.findByFullCidIn(fullCids).map { it.sellfoxSku }.toSet()
}

@Repository
class SellfoxSyncRunRepositoryImpl(
    private val jpa: SellfoxSyncRunJpaRepository,
) : SellfoxSyncRunRepository {

    override fun save(run: SellfoxSyncRun): SellfoxSyncRun {
        val row = run.id?.let { jpa.findById(it).orElse(null) } ?: SellfoxSyncRunDO()
        row.mode = run.mode.name
        row.triggerSource = run.trigger.name
        row.status = run.status.name
        row.triggeredBy = run.triggeredBy
        row.startedAt = run.startedAt
        row.finishedAt = run.finishedAt
        row.recordsRead = run.recordsRead
        row.recordsWritten = run.recordsWritten
        row.recordsSkipped = run.recordsSkipped
        row.errorMessage = run.errorMessage
        row.summary = run.summary
        return jpa.save(row).toDomain()
    }

    override fun recent(limit: Int): List<SellfoxSyncRun> =
        jpa.findAllByOrderByStartedAtDesc(PageRequest.of(0, limit)).map { it.toDomain() }

    override fun isRunning(): Boolean = jpa.existsByStatus(RunStatus.RUNNING.name)

    override fun failInterrupted(reason: String, at: Instant): Int {
        val stale = jpa.findByStatus(RunStatus.RUNNING.name)
        stale.forEach { row ->
            row.status = RunStatus.FAILED.name
            row.finishedAt = at
            row.errorMessage = reason
        }
        jpa.saveAll(stale)
        return stale.size
    }

    private fun SellfoxSyncRunDO.toDomain() = SellfoxSyncRun(
        id = id,
        mode = SyncMode.valueOf(mode),
        trigger = TriggerSource.valueOf(triggerSource),
        status = RunStatus.valueOf(status),
        triggeredBy = triggeredBy,
        startedAt = startedAt,
        finishedAt = finishedAt,
        recordsRead = recordsRead,
        recordsWritten = recordsWritten,
        recordsSkipped = recordsSkipped,
        errorMessage = errorMessage,
        summary = summary,
    )
}
