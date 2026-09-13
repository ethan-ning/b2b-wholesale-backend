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
 * `ddl-auto: validate` means the context will not start unless every @Entity lines up with
 * the table Flyway created, so most of this class is the setup succeeding. The assertions
 * cover what validation cannot see: constraints and defaults.
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

    /**
     * V2 adds this, so it is the first thing in the schema that does not come from V1. A
     * migration that did not run leaves every admin login working and only the forced
     * change silently absent.
     */
    @Test
    fun `the later migrations have run, not only the first`() {
        val column = em.createNativeQuery(
            """
            SELECT is_nullable || ' ' || column_default
            FROM information_schema.columns
            WHERE table_name = 'admin_user' AND column_name = 'must_change_password'
            """
        ).resultList.map { it.toString() }

        assertEquals(listOf("NO false"), column)
    }

    @Test
    fun `an admin defaults to not needing a password change`() {
        // Admins who already existed must not be locked out by the new column.
        em.createNativeQuery(
            """
            INSERT INTO admin_user (email, password_hash, name, role)
            VALUES ('existing@example.com', 'x', 'Existing', 'ADMIN')
            """
        ).executeUpdate()

        val forced = em.createNativeQuery(
            "SELECT must_change_password FROM admin_user WHERE email = 'existing@example.com'"
        ).singleResult

        assertEquals(false, forced)
    }

    @Test
    fun `the pricing tiers are seeded with fixed ids and their standing discounts`() {
        // tier_price rows reference these, so insertion order must not decide them.
        val tiers = em.createNativeQuery(
            "SELECT id, name, discount_percent FROM customer_tier ORDER BY sort_order"
        ).resultList.map { (it as Array<*>).let { row -> "${row[0]}:${row[1]}:${row[2]}" } }

        // Default pays list; the other two carry the rates the catalogue was already using.
        assertEquals(listOf("3:Default:0.00", "2:Silver:7.00", "1:Gold:18.00"), tiers)
    }

    /**
     * Requirement, not a happy accident of the schema: a product going away takes its
     * variants and their prices with it, or the price book fills with rows keyed to SKUs
     * that no longer exist.
     */
    @Test
    fun `removing a product clears the tier prices hanging off its SKUs`() {
        em.createNativeQuery(
            "INSERT INTO product (spu_code, name, base_wholesale_price) VALUES ('CASCADE-1', 'Probe', 10.00)"
        ).executeUpdate()
        val productId = em.createNativeQuery("SELECT id FROM product WHERE spu_code = 'CASCADE-1'")
            .singleResult as Number
        em.createNativeQuery(
            "INSERT INTO product_variant (product_id, sku, pack_quantity, status) " +
                "VALUES (${productId.toLong()}, 'CASCADE-1-A', 1, 'ACTIVE')"
        ).executeUpdate()
        em.createNativeQuery(
            "INSERT INTO tier_price (sku, tier_id, price) VALUES ('CASCADE-1-A', 1, 9.00)"
        ).executeUpdate()
        em.flush()

        fun priceRows() = (em.createNativeQuery(
            "SELECT count(*) FROM tier_price WHERE sku = 'CASCADE-1-A'"
        ).singleResult as Number).toInt()
        assertEquals(1, priceRows())

        em.createNativeQuery("DELETE FROM product WHERE id = ${productId.toLong()}").executeUpdate()
        em.flush()

        assertEquals(0, priceRows())
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
