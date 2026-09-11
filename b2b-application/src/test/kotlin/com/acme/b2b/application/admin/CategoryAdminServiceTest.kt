package com.acme.b2b.application.admin

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.Category
import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductVisibility
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.domain.catalog.StockLevel
import com.acme.b2b.types.Money
import com.acme.b2b.types.PackQuantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.VariantAxis
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryAdminServiceTest {

    private fun product(id: Long, spu: String, categoryIds: List<Long>, primary: Long?) = Product(
        id = id,
        spuCode = SpuCode(spu),
        name = "Product $id",
        brand = null,
        description = null,
        baseWholesalePrice = Money.of("10.00"),
        locationCode = null,
        variantAxis = VariantAxis.SIZE,
        attributes = emptyMap(),
        visibility = ProductVisibility.VISIBLE,
        categoryIds = categoryIds,
        primaryCategoryId = primary,
        images = emptyList(),
        variants = listOf(
            ProductVariant(
                id = null,
                sku = SkuCode("$spu-M"),
                variantValue = "M",
                packQuantity = PackQuantity(1),
                mapPrice = null,
                upc = null,
                weight = null,
                sortOrder = 0,
                active = true,
                stock = StockLevel(5, 0, Instant.EPOCH),
            )
        ),
    )

    /** Apparel(1) > Gloves(2) > Winter(3), the deepest the taxonomy is allowed to go. */
    private fun threeLevels() = InMemoryCategoryRepository(
        listOf(
            Category(1, "Apparel", "apparel", null, 0),
            Category(2, "Gloves", "gloves", 1, 0),
            Category(3, "Winter", "winter", 2, 0),
        )
    )

    // ─── Depth limit ─────────────────────────────────────────────────────

    @Test
    fun `a fourth level is refused`() {
        val categories = threeLevels()
        val service = CategoryAdminService(categories, InMemoryProductRepository())

        val failure = assertFailsWith<UseCaseViolation> {
            service.create(CreateCategoryCommand(name = "Heated", parentId = 3))
        }
        assertTrue(failure.message!!.contains("3 levels deep"), failure.message)
    }

    @Test
    fun `a third level is allowed`() {
        val categories = InMemoryCategoryRepository(
            listOf(
                Category(1, "Apparel", "apparel", null, 0),
                Category(2, "Gloves", "gloves", 1, 0),
            )
        )
        val service = CategoryAdminService(categories, InMemoryProductRepository())

        val created = service.create(CreateCategoryCommand(name = "Winter", parentId = 2))

        assertEquals(3, created.depth)
        // Nothing may hang off it, and the admin can see that before trying.
        assertFalse(created.canAddChild)
    }

    @Test
    fun `the tree reports where the ceiling is`() {
        val service = CategoryAdminService(threeLevels(), InMemoryProductRepository())

        val root = service.tree().single()
        assertTrue(root.canAddChild)
        assertTrue(root.children.single().canAddChild)
        assertFalse(root.children.single().children.single().canAddChild)
    }

    // ─── Rolled-up counts ────────────────────────────────────────────────

    @Test
    fun `a parent counts what is filed beneath it`() {
        val categories = threeLevels()
        categories.filings[1] = mutableSetOf(10)          // directly under Apparel
        categories.filings[2] = mutableSetOf(20, 21)      // under Gloves
        categories.filings[3] = mutableSetOf(30)          // under Winter
        val service = CategoryAdminService(categories, InMemoryProductRepository())

        val apparel = service.tree().single()
        assertEquals(1, apparel.productCount)
        assertEquals(4, apparel.totalProductCount)

        val gloves = apparel.children.single()
        assertEquals(2, gloves.productCount)
        assertEquals(3, gloves.totalProductCount)

        val winter = gloves.children.single()
        assertEquals(1, winter.productCount)
        assertEquals(1, winter.totalProductCount)
    }

    @Test
    fun `a product filed under a parent and its child is counted once`() {
        val categories = threeLevels()
        categories.filings[1] = mutableSetOf(10)
        categories.filings[2] = mutableSetOf(10)
        val service = CategoryAdminService(categories, InMemoryProductRepository())

        assertEquals(1, service.tree().single().totalProductCount)
    }

    // ─── Delete ──────────────────────────────────────────────────────────

    @Test
    fun `deleting a category unfiles its products rather than refusing`() {
        val categories = threeLevels()
        val products = InMemoryProductRepository(
            listOf(product(10, "GL100-BLK", categoryIds = listOf(2, 3), primary = 2))
        )
        val service = CategoryAdminService(categories, products)

        service.delete(3)

        assertNull(categories.findById(3))
        val survivor = products.findById(10)!!
        assertEquals(listOf(2L), survivor.categoryIds)
    }

    @Test
    fun `deleting the primary category hands the slot to another`() {
        val categories = threeLevels()
        val products = InMemoryProductRepository(
            listOf(product(10, "GL100-BLK", categoryIds = listOf(2, 3), primary = 3))
        )
        val service = CategoryAdminService(categories, products)

        service.delete(3)

        // Filed under Gloves and placed there too — not filed with no primary at all.
        assertEquals(2L, products.findById(10)!!.primaryCategoryId)
    }

    @Test
    fun `a product filed nowhere else ends up with no categories, but still exists`() {
        val categories = threeLevels()
        val products = InMemoryProductRepository(
            listOf(product(10, "GL100-BLK", categoryIds = listOf(3), primary = 3))
        )
        val service = CategoryAdminService(categories, products)

        service.delete(3)

        val survivor = products.findById(10)!!
        assertTrue(survivor.categoryIds.isEmpty())
        assertNull(survivor.primaryCategoryId)
    }

    @Test
    fun `sub-categories still block a delete`() {
        val service = CategoryAdminService(threeLevels(), InMemoryProductRepository())

        assertFailsWith<UseCaseViolation> { service.delete(2) }
    }

    @Test
    fun `a populated leaf reports itself deletable`() {
        val categories = threeLevels()
        categories.filings[3] = mutableSetOf(10, 11)
        val service = CategoryAdminService(categories, InMemoryProductRepository())

        val winter = service.tree().single().children.single().children.single()
        assertTrue(winter.deletable)
        assertNull(winter.blockedReason)
    }
}
