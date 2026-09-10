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
import jakarta.persistence.EntityManager
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
                weightGrams = link.weightGrams?.toBigDecimal(),
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
            weightGrams = it.weightGrams?.toDouble(),
            lastSeenAt = it.lastSeenAt,
        )
    }

    override fun deleteSkusNotIn(keep: Set<String>): Int {
        val stale = jpa.findAll().filterNot { it.sellfoxSku in keep }
        jpa.deleteAll(stale)
        return stale.size
    }
}

@Repository
class SellfoxSyncRunRepositoryImpl(
    private val jpa: SellfoxSyncRunJpaRepository,
    private val em: EntityManager,
) : SellfoxSyncRunRepository {

    override fun save(run: SellfoxSyncRun): SellfoxSyncRun = jpa.save(run.toRow()).toDomain()

    private fun SellfoxSyncRun.toRow(): SellfoxSyncRunDO {
        val row = id?.let { jpa.findById(it).orElse(null) } ?: SellfoxSyncRunDO()
        row.mode = mode.name
        row.triggerSource = trigger.name
        row.status = status.name
        row.triggeredBy = triggeredBy
        row.startedAt = startedAt
        row.finishedAt = finishedAt
        row.recordsRead = recordsRead
        row.recordsWritten = recordsWritten
        row.recordsSkipped = recordsSkipped
        row.errorMessage = errorMessage
        row.summary = summary
        return row
    }

    override fun recent(limit: Int): List<SellfoxSyncRun> =
        jpa.findAllByOrderByStartedAtDesc(PageRequest.of(0, limit)).map { it.toDomain() }

    override fun isRunning(): Boolean = jpa.existsByStatus(RunStatus.RUNNING.name)

    /**
     * Lets the database decide, and asks it in a way that can be turned down.
     *
     * ON CONFLICT rather than catching the violation: a failed constraint aborts the
     * Postgres transaction, so a caught exception leaves nothing usable to carry on with —
     * including the read that would report what happened. DO NOTHING returns no row
     * instead, and the transaction survives to be told about it.
     *
     * The conflict target repeats `one_running_sync`'s definition because that is how
     * Postgres identifies a partial index.
     */
    override fun startExclusively(run: SellfoxSyncRun): SellfoxSyncRun? {
        val id = em.createNativeQuery(
            """
            INSERT INTO sellfox_sync_run (mode, trigger_source, status, triggered_by, started_at)
            VALUES (?1, ?2, 'RUNNING', ?3, ?4)
            ON CONFLICT ((TRUE)) WHERE status = 'RUNNING' DO NOTHING
            RETURNING id
            """
        )
            .setParameter(1, run.mode.name)
            .setParameter(2, run.trigger.name)
            .setParameter(3, run.triggeredBy)
            .setParameter(4, run.startedAt)
            .resultList
            .firstOrNull() as Number? ?: return null

        return run.copy(id = id.toLong())
    }

    override fun failInterrupted(reason: String, at: Instant, startedBefore: Instant): Int {
        val stale = jpa.findByStatusAndStartedAtBefore(RunStatus.RUNNING.name, startedBefore)
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
