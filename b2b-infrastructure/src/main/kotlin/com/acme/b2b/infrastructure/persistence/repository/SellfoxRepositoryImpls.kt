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

    override fun setCategorySelected(cid: String, selected: Boolean) {
        val row = categories.findById(cid).orElseThrow {
            NoSuchElementException("No Sellfox category $cid; run a catalog sync to discover it")
        }
        row.selected = selected
        categories.save(row)
    }

    override fun setWarehouseSelected(warehouseId: Long, selected: Boolean) {
        val row = warehouses.findById(warehouseId).orElseThrow {
            NoSuchElementException("No Sellfox warehouse $warehouseId; run an inventory sync to discover it")
        }
        row.selected = selected
        warehouses.save(row)
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
                baseSellfoxSku = link.baseSellfoxSku,
                lastSeenAt = link.lastSeenAt,
            )
        )
    }

    override fun findAll(): List<SellfoxSkuLink> = jpa.findAll().map {
        SellfoxSkuLink(it.sellfoxSku, it.commodityId, it.fullCid, it.baseSellfoxSku, it.lastSeenAt)
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
        row.job = run.job.name
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

    override fun recent(job: SellfoxJob?, limit: Int): List<SellfoxSyncRun> {
        val page = PageRequest.of(0, limit)
        val rows = if (job == null) jpa.findAllByOrderByStartedAtDesc(page)
        else jpa.findByJobOrderByStartedAtDesc(job.name, page)
        return rows.map { it.toDomain() }
    }

    override fun isRunning(job: SellfoxJob): Boolean =
        jpa.existsByJobAndStatus(job.name, RunStatus.RUNNING.name)

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
        job = SellfoxJob.valueOf(job),
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

/**
 * Stock writes, straight at the variant rows. No product aggregate is loaded: a quarter-
 * hourly job touching a few thousand SKUs cannot afford to hydrate a few thousand
 * aggregates to change two integers on each.
 */
@Repository
class ProductStockRepositoryImpl(
    private val variants: com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository,
) : com.acme.b2b.domain.catalog.ProductStockRepository {

    override fun applyStock(updates: List<com.acme.b2b.domain.catalog.SkuStockUpdate>): Int {
        if (updates.isEmpty()) return 0
        val bySku = updates.associateBy { it.sku }
        val rows = variants.findBySkuIn(bySku.keys)
        rows.forEach { row ->
            val update = bySku.getValue(row.sku)
            row.availableStock = update.available
            row.incomingStock = update.incoming
            row.stockSyncedAt = update.syncedAt
        }
        variants.saveAll(rows)
        return rows.size
    }
}
