package com.acme.b2b.infrastructure.persistence

import com.acme.b2b.domain.catalog.RegroupedFamily
import com.acme.b2b.domain.catalog.RegroupedSku
import com.acme.b2b.domain.catalog.SkuStockUpdate
import com.acme.b2b.domain.catalog.WarehouseStock
import com.acme.b2b.infrastructure.PostgresTest
import com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.VariantWarehouseStockJpaRepository
import com.acme.b2b.infrastructure.persistence.repository.ProductGroupingRepositoryImpl
import com.acme.b2b.infrastructure.persistence.repository.ProductStockRepositoryImpl
import com.acme.b2b.infrastructure.persistence.repository.VariantStockBreakdownRepositoryImpl
import com.acme.b2b.types.VariantAxis
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regrouping and stock, against the real tables.
 *
 * These are the operations no aggregate can express — a SKU leaving one product for
 * another, a row being replaced rather than merged — so they are written at row level and
 * depend on Postgres behaving the way the code assumes. A fake would agree with whatever
 * the code did; this does not.
 */
@Import(
    ProductGroupingRepositoryImpl::class,
    ProductStockRepositoryImpl::class,
    VariantStockBreakdownRepositoryImpl::class,
)
class GroupingRepositoryIT : PostgresTest() {

    @Autowired private lateinit var grouping: ProductGroupingRepositoryImpl
    @Autowired private lateinit var stock: ProductStockRepositoryImpl
    @Autowired private lateinit var breakdown: VariantStockBreakdownRepositoryImpl
    @Autowired private lateinit var products: ProductJpaRepository
    @Autowired private lateinit var variants: ProductVariantJpaRepository
    @Autowired private lateinit var warehouseStock: VariantWarehouseStockJpaRepository
    @Autowired private lateinit var em: EntityManager

    private fun sku(code: String, value: String?, qty: Int = 1, order: Int = 0) =
        RegroupedSku(code, value, qty, order, null)

    private fun family(spu: String, vararg members: RegroupedSku) =
        RegroupedFamily(spu, "Family $spu", VariantAxis.PACK_QUANTITY, members.toList())

    private fun clear() {
        em.createNativeQuery("DELETE FROM variant_warehouse_stock").executeUpdate()
        em.createNativeQuery("DELETE FROM tier_price").executeUpdate()
        em.createNativeQuery("DELETE FROM product_variant").executeUpdate()
        em.createNativeQuery("DELETE FROM product_category").executeUpdate()
        em.createNativeQuery("DELETE FROM product").executeUpdate()
        em.flush()
        em.clear()
    }

    @Test
    fun `a first regroup creates the products and their SKUs`() {
        clear()

        val outcome = grouping.regroup(
            listOf(family("RB-1", sku("RB-1-1", "1", 1, 0), sku("RB-1-2", "2", 2, 1)))
        )

        assertEquals(1, outcome.productsCreated)
        assertEquals(2, outcome.skusCreated)
        val created = products.findBySpuCode("RB-1")!!
        // Hidden, because nothing has priced it — the same rule the API enforces.
        assertEquals("HIDDEN", created.visibility)
        assertEquals("SELLFOX", created.source)
        assertEquals(2, variants.findBySkuIn(setOf("RB-1-1", "RB-1-2")).size)
    }

    @Test
    fun `a SKU that changes product is reparented, not duplicated`() {
        clear()
        grouping.regroup(listOf(family("OLD-1", sku("MOVER-1", "1"))))
        em.flush(); em.clear()

        // The unique constraint on product_variant.sku means this cannot be an insert
        // followed by a delete — it has to be a move.
        val outcome = grouping.regroup(listOf(family("NEW-1", sku("MOVER-1", "1"))))

        assertEquals(1, outcome.skusMoved)
        assertEquals(0, outcome.skusCreated)
        em.flush(); em.clear()
        val moved = variants.findBySkuIn(setOf("MOVER-1")).single()
        assertEquals("NEW-1", moved.product!!.spuCode)
        // The product it left held nothing else, so the shell went with it.
        assertNull(products.findBySpuCode("OLD-1"))
        assertEquals(1, outcome.emptyProductsRemoved)
    }

    @Test
    fun `a SKU no longer placed is withdrawn, and keeps its pricing`() {
        clear()
        grouping.regroup(listOf(family("SL-1", sku("SL-1-A", "A"), sku("SL-1-B", "B"))))
        em.flush()
        em.createNativeQuery(
            "INSERT INTO tier_price (sku, tier_id, price, min_qty) VALUES ('SL-1-B', 1, 9.99, 1)"
        ).executeUpdate()
        em.flush(); em.clear()

        val outcome = grouping.regroup(listOf(family("SL-1", sku("SL-1-A", "A"))))

        assertEquals(1, outcome.skusWithdrawn)
        em.flush(); em.clear()
        val withdrawn = variants.findBySkuIn(setOf("SL-1-B")).single()
        // Marked, not deleted: tier_price cascades from the SKU, and a SKU coming back on
        // sale should find its pricing intact.
        assertEquals("DISCONTINUED", withdrawn.status)
        val keptPrice = em.createNativeQuery("SELECT count(*) FROM tier_price WHERE sku = 'SL-1-B'")
            .singleResult as Number
        assertEquals(1, keptPrice.toInt())
        // Its sibling was untouched.
        assertEquals("ACTIVE", variants.findBySkuIn(setOf("SL-1-A")).single().status)
    }

    @Test
    fun `a withdrawn SKU comes back on sale when it is placed again`() {
        clear()
        grouping.regroup(listOf(family("SL-2", sku("SL-2-A", "A"))))
        em.flush(); em.clear()
        grouping.regroup(listOf(family("SL-2", sku("SL-2-Z", "Z"))))
        em.flush(); em.clear()

        grouping.regroup(listOf(family("SL-2", sku("SL-2-A", "A"), sku("SL-2-Z", "Z"))))

        em.flush(); em.clear()
        assertTrue(variants.findBySkuIn(setOf("SL-2-A", "SL-2-Z")).all { it.status == "ACTIVE" })
    }

    @Test
    fun `deleting a SKU takes its tier prices with it`() {
        clear()
        grouping.regroup(listOf(family("CASC-1", sku("CASC-1-A", "A"))))
        em.flush()
        em.createNativeQuery(
            "INSERT INTO tier_price (sku, tier_id, price, min_qty) VALUES ('CASC-1-A', 1, 5.00, 1)"
        ).executeUpdate()
        em.flush(); em.clear()

        em.createNativeQuery("DELETE FROM product_variant WHERE sku = 'CASC-1-A'").executeUpdate()
        em.flush()

        val orphans = em.createNativeQuery("SELECT count(*) FROM tier_price WHERE sku = 'CASC-1-A'")
            .singleResult as Number
        assertEquals(0, orphans.toInt())
    }

    @Test
    fun `products the grouping no longer names are hidden rather than deleted`() {
        clear()
        grouping.regroup(listOf(family("KEEP-1", sku("KEEP-1-A", "A")), family("DROP-1", sku("DROP-1-A", "A"))))
        em.flush()
        em.createNativeQuery("UPDATE product SET visibility = 'VISIBLE' WHERE source = 'SELLFOX'").executeUpdate()
        em.flush(); em.clear()

        val hidden = grouping.deactivateProductsNotIn(setOf("KEEP-1"))

        assertEquals(1, hidden)
        em.flush(); em.clear()
        assertEquals("HIDDEN", products.findBySpuCode("DROP-1")!!.visibility)
        assertEquals("VISIBLE", products.findBySpuCode("KEEP-1")!!.visibility)
    }

    // ─── Stock ───────────────────────────────────────────────────────────

    @Test
    fun `applying stock writes the total and the rows behind it`() {
        clear()
        grouping.regroup(listOf(family("ST-1", sku("ST-1-A", "A"))))
        em.flush(); em.clear()

        val matched = stock.applyStock(
            listOf(
                SkuStockUpdate(
                    sku = "ST-1-A",
                    available = 42,
                    incoming = 300,
                    syncedAt = Instant.parse("2026-09-10T00:00:00Z"),
                    byWarehouse = listOf(WarehouseStock(1, 40, 300), WarehouseStock(2, 2, 0)),
                )
            )
        )

        assertEquals(1, matched)
        em.flush(); em.clear()
        val variant = variants.findBySkuIn(setOf("ST-1-A")).single()
        assertEquals(42, variant.availableStock)
        assertEquals(300, variant.incomingStock)
        assertEquals(2, warehouseStock.findLinesBySkuIn(listOf("ST-1-A")).size)
    }

    @Test
    fun `a warehouse that has left the scope loses its row`() {
        clear()
        grouping.regroup(listOf(family("ST-2", sku("ST-2-A", "A"))))
        em.flush(); em.clear()
        val at = Instant.parse("2026-09-10T00:00:00Z")
        stock.applyStock(
            listOf(SkuStockUpdate("ST-2-A", 42, 0, at, listOf(WarehouseStock(1, 40, 0), WarehouseStock(2, 2, 0))))
        )
        em.flush(); em.clear()

        // Warehouse 2 is no longer in scope, so it contributes nothing to the new total
        // and its old row must not survive to suggest otherwise.
        stock.applyStock(listOf(SkuStockUpdate("ST-2-A", 40, 0, at, listOf(WarehouseStock(1, 40, 0)))))

        em.flush(); em.clear()
        val lines = breakdown.findBySkus(listOf("ST-2-A"))
        assertEquals(listOf(1L), lines.map { it.warehouseId })
        assertEquals(40, lines.single().available)
    }

    @Test
    fun `stock for a SKU the catalog does not have is skipped, not failed`() {
        clear()
        grouping.regroup(listOf(family("ST-3", sku("ST-3-A", "A"))))
        em.flush(); em.clear()
        val at = Instant.parse("2026-09-10T00:00:00Z")

        // Sellfox holds far more SKUs than the portal carries. The difference is normal.
        val matched = stock.applyStock(
            listOf(
                SkuStockUpdate("ST-3-A", 1, 0, at, listOf(WarehouseStock(1, 1, 0))),
                SkuStockUpdate("NOT-OURS-1", 99, 0, at, listOf(WarehouseStock(1, 99, 0))),
            )
        )

        assertEquals(1, matched)
        em.flush(); em.clear()
        assertTrue(breakdown.findBySkus(listOf("NOT-OURS-1")).isEmpty())
    }

    @Test
    fun `the breakdown names the warehouse when the registry knows it`() {
        clear()
        grouping.regroup(listOf(family("ST-4", sku("ST-4-A", "A"))))
        em.createNativeQuery(
            "INSERT INTO sellfox_warehouse (warehouse_id, name, selected, last_seen_at) " +
                "VALUES (77, 'TX Overseas', TRUE, NOW()) ON CONFLICT DO NOTHING"
        ).executeUpdate()
        em.flush(); em.clear()
        val at = Instant.parse("2026-09-10T00:00:00Z")
        stock.applyStock(listOf(SkuStockUpdate("ST-4-A", 5, 0, at, listOf(WarehouseStock(77, 5, 0)))))
        em.flush(); em.clear()

        assertEquals("TX Overseas", breakdown.findBySkus(listOf("ST-4-A")).single().warehouseName)
    }

    @Test
    fun `a warehouse the registry has forgotten still yields its row`() {
        clear()
        grouping.regroup(listOf(family("ST-5", sku("ST-5-A", "A"))))
        em.flush(); em.clear()
        val at = Instant.parse("2026-09-10T00:00:00Z")
        stock.applyStock(listOf(SkuStockUpdate("ST-5-A", 5, 0, at, listOf(WarehouseStock(4242, 5, 0)))))
        em.flush(); em.clear()

        // A LEFT JOIN, so stock in a warehouse that has left the scope is still reported —
        // blank name rather than a missing row.
        val line = breakdown.findBySkus(listOf("ST-5-A")).single()
        assertEquals(4242, line.warehouseId)
        assertEquals("", line.warehouseName)
    }
}
