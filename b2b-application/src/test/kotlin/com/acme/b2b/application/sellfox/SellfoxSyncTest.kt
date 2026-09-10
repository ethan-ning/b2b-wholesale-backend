package com.acme.b2b.application.sellfox

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.RegroupOutcome
import com.acme.b2b.domain.sellfox.RunStatus
import com.acme.b2b.domain.sellfox.SellfoxCategoryScope
import com.acme.b2b.domain.sellfox.SellfoxSkuLink
import com.acme.b2b.domain.sellfox.SellfoxStock
import com.acme.b2b.domain.sellfox.SellfoxWarehouse
import com.acme.b2b.domain.sellfox.SellfoxWarehouseScope
import com.acme.b2b.domain.sellfox.SyncCounts
import com.acme.b2b.domain.sellfox.SyncMode
import com.acme.b2b.domain.sellfox.TriggerSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync end to end, without a network.
 *
 * Three steps in the only order that works: the import records what Sellfox said, the
 * regroup turns those facts into products, and the stock pass counts what the regroup
 * just placed. Each is tested for the thing it alone is responsible for.
 */
class SellfoxSyncTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC)
    private val now = clock.instant()

    private fun link(sku: String, spu: String? = null, base: String? = null, qty: Int? = null) =
        SellfoxSkuLink(
            sellfoxSku = sku,
            commodityId = "c-$sku",
            fullCid = "10-20-30",
            declaredSpu = spu,
            baseSellfoxSku = base,
            baseQuantity = qty,
            commodityName = "Part",
            weightGrams = null,
            lastSeenAt = Instant.EPOCH,
        )

    // ─── The importer records facts and nothing else ─────────────────────

    @Test
    fun `an import writes one link per in-scope SKU and builds no products`() {
        val scope = InMemoryScopeRepository(selectedCategories = setOf("10-20"))
        val links = InMemorySkuLinkRepository()
        val importer = SellfoxCatalogImporter(
            FakeCatalogPort(listOf(commodity("A-1"), commodity("A-2", packs = "A-1" to 2))),
            scope,
            links,
        )

        val counts = SyncCounts()
        importer.recordFacts(counts, now)

        assertEquals(listOf("A-1", "A-2"), links.findAll().map { it.sellfoxSku })
        // The pack relationship is recorded as Sellfox stated it, not as a conclusion.
        assertEquals("A-1", links.findAll()[1].baseSellfoxSku)
        assertEquals(2, links.findAll()[1].baseQuantity)
        assertEquals(2, counts.read)
    }

    @Test
    fun `a commodity outside the selected categories is skipped`() {
        val links = InMemorySkuLinkRepository()
        val importer = SellfoxCatalogImporter(
            FakeCatalogPort(listOf(commodity("IN-1", fullCid = "10-20-30"), commodity("OUT-1", fullCid = "77-88-99"))),
            InMemoryScopeRepository(selectedCategories = setOf("10-20")),
            links,
        )

        val counts = SyncCounts()
        importer.recordFacts(counts, now)

        assertEquals(listOf("IN-1"), links.findAll().map { it.sellfoxSku })
        assertEquals(1, counts.skipped)
    }

    @Test
    fun `an inactive commodity is never recorded`() {
        val links = InMemorySkuLinkRepository()
        // Only 在售 commodities are imported, so a product going off sale leaves the
        // catalogue by not being imported rather than by any portal flag.
        SellfoxCatalogImporter(
            FakeCatalogPort(listOf(commodity("GONE-1", active = false))),
            InMemoryScopeRepository(selectedCategories = setOf("10-20")),
            links,
        ).recordFacts(SyncCounts(), now)

        assertTrue(links.findAll().isEmpty())
    }

    @Test
    fun `a SKU this run did not see is forgotten`() {
        val links = InMemorySkuLinkRepository(listOf(link("STALE-1"), link("KEEP-1")))
        val importer = SellfoxCatalogImporter(
            FakeCatalogPort(listOf(commodity("KEEP-1"))),
            InMemoryScopeRepository(selectedCategories = setOf("10-20")),
            links,
        )

        val summary = importer.recordFacts(SyncCounts(), now)

        assertEquals(listOf("KEEP-1"), links.findAll().map { it.sellfoxSku })
        assertTrue(summary.contains("1 no longer in scope"), summary)
    }

    @Test
    fun `a first run with nothing selected fills the picker and records nothing`() {
        val scope = InMemoryScopeRepository(selectedCategories = emptySet())
        val links = InMemorySkuLinkRepository()

        val summary = SellfoxCatalogImporter(
            FakeCatalogPort(listOf(commodity("A-1", fullCid = "10-20-30"))),
            scope,
            links,
        ).recordFacts(SyncCounts(), now)

        assertTrue(links.findAll().isEmpty())
        assertEquals(listOf("10-20"), scope.categoriesSeen.map { it.cid })
        assertTrue(summary.contains("none selected"), summary)
    }

    @Test
    fun `categories are discovered two levels down`() {
        val scope = InMemoryScopeRepository()
        SellfoxCatalogImporter(
            FakeCatalogPort(
                listOf(
                    commodity("A-1", fullCid = "10-20-30-"),
                    commodity("B-1", fullCid = "10-20-40-"),
                    commodity("C-1", fullCid = "99-"),
                )
            ),
            scope,
            InMemorySkuLinkRepository(),
        ).recordFacts(SyncCounts(), now)

        // Two commodities collapse into one group; a one-level path is its own group
        // rather than being dropped.
        assertEquals(setOf("10-20", "99"), scope.categoriesSeen.map { it.cid }.toSet())
        assertEquals(2, scope.categoriesSeen.single { it.cid == "10-20" }.commodityCount)
    }

    // ─── The regrouper is the only thing that groups ─────────────────────

    @Test
    fun `the regroup reads the recorded links, not Sellfox`() {
        val grouping = RecordingGroupingRepository()
        val catalog = FakeCatalogPort(emptyList())
        val links = InMemorySkuLinkRepository(
            listOf(link("RB-1", base = null), link("RB-2", base = "RB-1", qty = 2))
        )

        SpuRegrouper(links, grouping).regroup(SyncCounts())

        assertEquals(0, catalog.calls)
        val family = grouping.families.single()
        assertEquals(listOf("RB-1", "RB-2"), family.members.map { it.sku })
        assertEquals(listOf(1, 2), family.members.map { it.packQuantity })
    }

    @Test
    fun `nothing recorded means nothing to group`() {
        val grouping = RecordingGroupingRepository()

        val summary = SpuRegrouper(InMemorySkuLinkRepository(), grouping).regroup(SyncCounts())

        assertTrue(summary.contains("nothing to group"), summary)
        assertTrue(grouping.families.isEmpty())
    }

    @Test
    fun `products holding none of the placed SKUs are deactivated`() {
        val grouping = RecordingGroupingRepository(deactivated = 3)
        val links = InMemorySkuLinkRepository(listOf(link("KEEP-1")))

        val summary = SpuRegrouper(links, grouping).regroup(SyncCounts())

        assertEquals(setOf("KEEP"), grouping.keptSpuCodes)
        assertTrue(summary.contains("3 deactivated"), summary)
    }

    @Test
    fun `an unchanged grouping says so rather than reporting zeroes`() {
        val grouping = RecordingGroupingRepository(RegroupOutcome(0, 0, 0, 0, 0), deactivated = 0)

        val summary = SpuRegrouper(InMemorySkuLinkRepository(listOf(link("A-1"))), grouping)
            .regroup(SyncCounts())

        assertTrue(summary.contains("already correct"), summary)
    }

    // ─── Stock sums across the selected warehouses ───────────────────────

    @Test
    fun `stock is summed per SKU and the breakdown is kept`() {
        val stock = RecordingStockRepository()
        val updater = SellfoxStockUpdater(
            FakeInventoryPort(
                warehouses = listOf(SellfoxWarehouse(1, "TX", 3), SellfoxWarehouse(2, "TN", 3)),
                stock = mapOf(
                    1L to listOf(SellfoxStock("A-1", 1, 40, 300)),
                    2L to listOf(SellfoxStock("A-1", 2, 2, 0)),
                ),
            ),
            InMemoryScopeRepository(selectedWarehouses = listOf(1, 2)),
            stock,
        )

        updater.refresh(SyncCounts(), now)

        val update = stock.updates.single()
        assertEquals(42, update.available)
        assertEquals(300, update.incoming)
        // The rows behind the sum survive, so an admin can see where a total came from.
        assertEquals(listOf(1L to 40, 2L to 2), update.byWarehouse.map { it.warehouseId to it.available })
    }

    @Test
    fun `only the selected warehouses are read`() {
        val inventory = FakeInventoryPort(
            warehouses = listOf(SellfoxWarehouse(1, "TX", 3), SellfoxWarehouse(9, "CN", 1)),
        )
        // Stock in a warehouse that cannot reach a US dealer must stay out of the number
        // they see, and that is enforced by never reading it.
        SellfoxStockUpdater(inventory, InMemoryScopeRepository(selectedWarehouses = listOf(1)), RecordingStockRepository())
            .refresh(SyncCounts(), now)

        assertEquals(listOf(1L), inventory.stockReadsFor)
    }

    @Test
    fun `no warehouse selected reads no stock`() {
        val inventory = FakeInventoryPort(warehouses = listOf(SellfoxWarehouse(1, "TX", 3)))

        val summary = SellfoxStockUpdater(
            inventory,
            InMemoryScopeRepository(selectedWarehouses = emptyList()),
            RecordingStockRepository(),
        ).refresh(SyncCounts(), now)

        assertTrue(inventory.stockReadsFor.isEmpty())
        assertTrue(summary.contains("none selected"), summary)
    }

    @Test
    fun `SKUs Sellfox holds but the catalog does not are skipped, not failed`() {
        val counts = SyncCounts()
        SellfoxStockUpdater(
            FakeInventoryPort(
                stock = mapOf(1L to listOf(SellfoxStock("MINE-1", 1, 5, 0), SellfoxStock("THEIRS-1", 1, 5, 0))),
            ),
            InMemoryScopeRepository(selectedWarehouses = listOf(1)),
            RecordingStockRepository(matched = 1),
        ).refresh(counts, now)

        assertEquals(1, counts.written)
        assertEquals(1, counts.skipped)
    }

    // ─── Orchestration ───────────────────────────────────────────────────

    private fun syncService(
        scope: InMemoryScopeRepository,
        runs: InMemorySyncRunRepository = InMemorySyncRunRepository(),
        links: InMemorySkuLinkRepository = InMemorySkuLinkRepository(),
        grouping: RecordingGroupingRepository = RecordingGroupingRepository(),
    ) = SellfoxSyncService(
        SellfoxCatalogImporter(FakeCatalogPort(listOf(commodity("A-1"))), scope, links),
        SpuRegrouper(links, grouping),
        SellfoxStockUpdater(FakeInventoryPort(), scope, RecordingStockRepository()),
        scope,
        SyncRunner(runs, clock),
        clock,
    )

    @Test
    fun `a full sync records facts, regroups, then counts stock`() {
        val scope = InMemoryScopeRepository(
            selectedCategories = setOf("10-20"),
            selectedWarehouses = listOf(1),
            knownCategories = listOf(SellfoxCategoryScope("10-20", "10-20", "Group", 1, true)),
            knownWarehouses = listOf(SellfoxWarehouseScope(1, "TX", 3, true, null)),
        )
        val links = InMemorySkuLinkRepository()
        val grouping = RecordingGroupingRepository()
        val runs = InMemorySyncRunRepository()

        val run = syncService(scope, runs, links, grouping).syncFull(TriggerSource.MANUAL, "admin@example.com")

        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(SyncMode.FULL, run.mode)
        assertEquals("admin@example.com", run.triggeredBy)
        // The import ran before the regroup: the regroup saw the SKU the import recorded.
        assertEquals(listOf("A-1"), links.findAll().map { it.sellfoxSku })
        assertEquals(listOf("A-1"), grouping.families.single().members.map { it.sku })
    }

    @Test
    fun `a sync is refused when the scope is half chosen`() {
        val scope = InMemoryScopeRepository(
            selectedCategories = setOf("10-20"),
            selectedWarehouses = emptyList(),
            knownCategories = listOf(SellfoxCategoryScope("10-20", "10-20", "Group", 1, true)),
            knownWarehouses = listOf(SellfoxWarehouseScope(1, "TX", 3, false, null)),
        )

        val failure = assertFailsWith<UseCaseViolation> {
            syncService(scope).syncFull(TriggerSource.MANUAL)
        }

        assertTrue(failure.message!!.contains("warehouse"), failure.message)
    }

    @Test
    fun `the first run is allowed with nothing selected, because nothing exists to select`() {
        val scope = InMemoryScopeRepository()

        val run = syncService(scope).syncFull(TriggerSource.SCHEDULED)

        assertEquals(RunStatus.SUCCESS, run.status)
        assertNull(run.triggeredBy)
    }

    @Test
    fun `a second run is refused while one is going`() {
        val runs = InMemorySyncRunRepository().apply { running = true }

        assertFailsWith<UseCaseViolation> {
            syncService(InMemoryScopeRepository(), runs).syncFull(TriggerSource.MANUAL)
        }
    }

    @Test
    fun `a failure is recorded with its counts rather than losing them`() {
        val runs = InMemorySyncRunRepository()
        val exploding = SellfoxSyncService(
            SellfoxCatalogImporter(
                object : com.acme.b2b.domain.sellfox.SellfoxCatalogPort {
                    override fun listCommodities(): List<com.acme.b2b.domain.sellfox.SellfoxCommodity> =
                        throw IllegalStateException("Sellfox is down")
                },
                InMemoryScopeRepository(),
                InMemorySkuLinkRepository(),
            ),
            SpuRegrouper(InMemorySkuLinkRepository(), RecordingGroupingRepository()),
            SellfoxStockUpdater(FakeInventoryPort(), InMemoryScopeRepository(), RecordingStockRepository()),
            InMemoryScopeRepository(),
            SyncRunner(runs, clock),
            clock,
        )

        assertFailsWith<IllegalStateException> { exploding.syncFull(TriggerSource.SCHEDULED) }

        val recorded = runs.all.single()
        assertEquals(RunStatus.FAILED, recorded.status)
        // The type as well as the message: "null" alone is what a bare NPE message gives.
        assertTrue(recorded.errorMessage!!.contains("IllegalStateException"), recorded.errorMessage)
        assertTrue(recorded.errorMessage!!.contains("Sellfox is down"), recorded.errorMessage)
    }

    @Test
    fun `an inventory run touches stock only`() {
        val scope = InMemoryScopeRepository(selectedWarehouses = listOf(1))
        val links = InMemorySkuLinkRepository()

        val run = syncService(scope, links = links).syncInventory(TriggerSource.SCHEDULED)

        assertEquals(SyncMode.INVENTORY, run.mode)
        assertTrue(links.findAll().isEmpty())
    }
}
