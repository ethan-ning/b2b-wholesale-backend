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

/**
 * Re-files SKUs when the grouping changes.
 *
 * Works at row level rather than through the Product aggregate: a SKU moving between
 * products cannot be expressed as saving one aggregate, and the unique constraint on
 * product_variant.sku means the move has to be a reparent rather than an insert-then-delete.
 */
@Repository
class ProductGroupingRepositoryImpl(
    private val products: com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository,
    private val variants: com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository,
) : com.acme.b2b.domain.catalog.ProductGroupingRepository {

    override fun regroup(
        families: List<com.acme.b2b.domain.catalog.RegroupedFamily>,
    ): com.acme.b2b.domain.catalog.RegroupOutcome {
        val bySpu = products.findBySourceIn(listOf(SELLFOX)).associateBy { it.spuCode }
        var created = 0
        var moved = 0

        families.forEach { family ->
            val target = bySpu[family.spuCode] ?: run {
                created++
                products.save(
                    com.acme.b2b.infrastructure.persistence.entity.ProductDO(
                        spuCode = family.spuCode,
                        name = family.name,
                        source = SELLFOX,
                        // Imported products are inactive until priced, and a product this
                        // run invents has never been priced.
                        status = com.acme.b2b.domain.catalog.ProductStatus.INACTIVE.name,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                )
            }
            target.variantAxis = family.axisLabel
            target.name = family.name
            target.updatedAt = Instant.now()
            products.save(target)

            family.members.forEach { member ->
                val row = variants.findBySkuIn(listOf(member.sku)).firstOrNull() ?: return@forEach
                if (row.product?.id != target.id) {
                    row.product = target
                    moved++
                }
                row.variantValue = member.variantValue
                row.packQuantity = member.packQuantity
                row.sortOrder = member.sortOrder
                variants.save(row)
            }
        }

        // Flush the reparenting before looking for empties: the rows that left are still
        // pending, so a product would otherwise still look occupied by SKUs it has lost.
        variants.flush()

        val emptied = products.findBySourceIn(listOf(SELLFOX)).filter { it.variants.isEmpty() }
        products.deleteAll(emptied)

        return com.acme.b2b.domain.catalog.RegroupOutcome(
            productsCreated = created,
            skusMoved = moved,
            emptyProductsRemoved = emptied.size,
        )
    }

    private companion object {
        const val SELLFOX = "SELLFOX"
    }
}
