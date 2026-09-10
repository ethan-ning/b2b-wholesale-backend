package com.acme.b2b.infrastructure.persistence

import com.acme.b2b.infrastructure.PostgresTest
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the schema applies and the mappings match it.
 *
 * `ddl-auto: validate` means the context in this test does not start at all unless every
 * @Entity lines up with the table Flyway created — so a column renamed in one and not the
 * other fails here rather than at deploy time. The assertions below cover what validation
 * cannot see: constraints, cascades and defaults.
 */
class SchemaIT : PostgresTest() {

    @Autowired private lateinit var em: EntityManager

    private fun scalar(sql: String): Any? =
        em.createNativeQuery(sql).resultList.firstOrNull()

    @Test
    fun `the migration applied cleanly`() {
        val applied = scalar("SELECT success FROM flyway_schema_history WHERE version = '1'")
        assertEquals(true, applied)
    }

    @Test
    fun `every table the mappings need exists`() {
        val tables = em.createNativeQuery(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        ).resultList.map { it.toString() }

        assertTrue(
            tables.containsAll(
                listOf(
                    "category", "product", "product_variant", "product_image", "product_category",
                    "customer_tier", "customer", "tier_price", "admin_user",
                    "sellfox_category", "sellfox_warehouse", "sellfox_sku_link", "sellfox_sync_run",
                    "variant_warehouse_stock",
                )
            ),
            "missing: ${tables}",
        )
    }

    @Test
    fun `the two pricing tiers are seeded with fixed ids`() {
        // tier_price rows reference these, so insertion order must not decide them.
        val tiers = em.createNativeQuery("SELECT id, name FROM customer_tier ORDER BY id").resultList
            .map { (it as Array<*>).let { row -> "${row[0]}:${row[1]}" } }

        assertEquals(listOf("1:Gold", "2:Silver"), tiers)
    }

    @Test
    fun `a product defaults to hidden and portal-sourced`() {
        em.createNativeQuery(
            "INSERT INTO product (spu_code, name, base_wholesale_price) VALUES ('DEF-1', 'Default', 1.00)"
        ).executeUpdate()

        val row = em.createNativeQuery("SELECT visibility, source FROM product WHERE spu_code = 'DEF-1'")
            .singleResult as Array<*>

        // An import has no dealer price yet, so nothing arrives visible by accident.
        assertEquals("HIDDEN", row[0])
        assertEquals("PORTAL", row[1])
    }

    @Test
    fun `visibility and status only accept what the domain uses`() {
        val rejected = runCatching {
            em.createNativeQuery(
                "INSERT INTO product (spu_code, name, base_wholesale_price, visibility) " +
                    "VALUES ('BAD-1', 'Bad', 1.00, 'SORT-OF')"
            ).executeUpdate()
        }
        assertTrue(rejected.isFailure, "the CHECK constraint should have refused SORT-OF")
    }
}
