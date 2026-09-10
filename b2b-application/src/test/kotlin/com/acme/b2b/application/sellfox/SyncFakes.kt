package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.ProductGroupingRepository
import com.acme.b2b.domain.catalog.ProductStockRepository
import com.acme.b2b.domain.catalog.RegroupOutcome
import com.acme.b2b.domain.catalog.RegroupedFamily
import com.acme.b2b.domain.catalog.SkuStockUpdate
import com.acme.b2b.domain.sellfox.SellfoxCatalogPort
import com.acme.b2b.domain.sellfox.SellfoxCategoryScope
import com.acme.b2b.domain.sellfox.SellfoxChild
import com.acme.b2b.domain.sellfox.SellfoxCommodity
import com.acme.b2b.domain.sellfox.SellfoxInventoryPort
import com.acme.b2b.domain.sellfox.SellfoxScopeRepository
import com.acme.b2b.domain.sellfox.SellfoxSkuLink
import com.acme.b2b.domain.sellfox.SellfoxSkuLinkRepository
import com.acme.b2b.domain.sellfox.SellfoxStock
import com.acme.b2b.domain.sellfox.SellfoxSyncRun
import com.acme.b2b.domain.sellfox.SellfoxSyncRunRepository
import com.acme.b2b.domain.sellfox.SellfoxWarehouse
import com.acme.b2b.domain.sellfox.SellfoxWarehouseScope
import java.time.Instant

/**
 * Stand-ins for the Sellfox side, so the whole sync can be driven without a network.
 * They record what was asked of them and return whatever the test set up.
 */

fun commodity(
    sku: String,
    name: String = "Part",
    fullCid: String = "10-20-30",
    declaredSpu: String? = null,
    packs: Pair<String, Int>? = null,
    weightGrams: Double? = null,
    active: Boolean = true,
) = SellfoxCommodity(
    commodityId = "c-$sku",
    sku = sku,
    name = name,
    fullCid = fullCid,
    fullName = "Supplier/Group/Leaf",
    declaredSpu = declaredSpu,
    weightGrams = weightGrams,
    children = packs?.let { (base, qty) -> listOf(SellfoxChild(base, qty)) }.orEmpty(),
    isActive = active,
)

class FakeCatalogPort(private val commodities: List<SellfoxCommodity>) : SellfoxCatalogPort {
    var calls = 0; private set
    override fun listCommodities(): List<SellfoxCommodity> {
        calls++
        return commodities
    }
}

class FakeInventoryPort(
    private val warehouses: List<SellfoxWarehouse> = emptyList(),
    private val stock: Map<Long, List<SellfoxStock>> = emptyMap(),
) : SellfoxInventoryPort {
    val stockReadsFor = mutableListOf<Long>()
    override fun listWarehouses() = warehouses
    override fun listStock(warehouseId: Long): List<SellfoxStock> {
        stockReadsFor += warehouseId
        return stock[warehouseId].orEmpty()
    }
}

class InMemoryScopeRepository(
    private var selectedCategories: Set<String> = emptySet(),
    private var selectedWarehouses: List<Long> = emptyList(),
    private var knownCategories: List<SellfoxCategoryScope> = emptyList(),
    private var knownWarehouses: List<SellfoxWarehouseScope> = emptyList(),
) : SellfoxScopeRepository {
    var categoriesSeen: List<SellfoxCategoryScope> = emptyList(); private set
    var warehousesSeen: List<SellfoxWarehouse> = emptyList(); private set

    override fun selectedCategoryIds() = selectedCategories
    override fun selectedWarehouseIds() = selectedWarehouses
    override fun categories() = knownCategories
    override fun warehouses() = knownWarehouses

    override fun refreshCategories(seen: List<SellfoxCategoryScope>, at: Instant) {
        categoriesSeen = seen
        knownCategories = seen
    }

    override fun refreshWarehouses(seen: List<SellfoxWarehouse>, at: Instant) {
        warehousesSeen = seen
        knownWarehouses = seen.map { SellfoxWarehouseScope(it.warehouseId, it.name, it.type, false, at) }
    }

    override fun selectCategories(cids: Set<String>) { selectedCategories = cids }
    override fun selectWarehouses(warehouseIds: Set<Long>) { selectedWarehouses = warehouseIds.toList() }
}

class InMemorySkuLinkRepository(seed: List<SellfoxSkuLink> = emptyList()) : SellfoxSkuLinkRepository {
    private val rows = linkedMapOf<String, SellfoxSkuLink>()

    init { seed.forEach { rows[it.sellfoxSku] = it } }

    override fun save(link: SellfoxSkuLink) { rows[link.sellfoxSku] = link }
    override fun findAll() = rows.values.toList()

    override fun deleteSkusNotIn(keep: Set<String>): Int {
        val going = rows.keys.filterNot { it in keep }
        going.forEach { rows.remove(it) }
        return going.size
    }
}

/** Records the grouping it was handed rather than materialising it. */
class RecordingGroupingRepository(
    private val outcome: RegroupOutcome = RegroupOutcome(0, 0, 0, 0, 0),
    private val deactivated: Int = 0,
) : ProductGroupingRepository {
    var families: List<RegroupedFamily> = emptyList(); private set
    var keptSpuCodes: Set<String> = emptySet(); private set

    override fun regroup(families: List<RegroupedFamily>): RegroupOutcome {
        this.families = families
        return outcome
    }

    override fun deactivateProductsNotIn(spuCodes: Set<String>): Int {
        keptSpuCodes = spuCodes
        return deactivated
    }
}

class RecordingStockRepository(private val matched: Int? = null) : ProductStockRepository {
    var updates: List<SkuStockUpdate> = emptyList(); private set
    override fun applyStock(updates: List<SkuStockUpdate>): Int {
        this.updates = updates
        return matched ?: updates.size
    }
}

class InMemorySyncRunRepository : SellfoxSyncRunRepository {
    private val rows = mutableListOf<SellfoxSyncRun>()
    private var nextId = 1L
    var running = false

    val all: List<SellfoxSyncRun> get() = rows.toList()

    override fun save(run: SellfoxSyncRun): SellfoxSyncRun {
        val stored = run.copy(id = run.id ?: nextId++)
        rows.removeAll { it.id == stored.id }
        rows += stored
        return stored
    }

    override fun recent(limit: Int) = rows.sortedByDescending { it.startedAt }.take(limit)
    override fun isRunning() = running
    override fun failInterrupted(reason: String, at: Instant) = 0
}
