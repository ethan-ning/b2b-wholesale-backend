package com.acme.b2b.application.admin

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.Category
import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.domain.catalog.ProductVisibility
import com.acme.b2b.domain.catalog.StockLevel
import com.acme.b2b.domain.catalog.WarehouseStockLine
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.domain.pricing.TierPrice
import com.acme.b2b.types.Money
import com.acme.b2b.types.PackQuantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.TierId
import com.acme.b2b.types.Quantity
import com.acme.b2b.types.VariantAxis
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule this service holds: a product no dealer could buy from must not be visible.
 *
 * Two routes can set visibility, the eye button and the edit form, and both are covered —
 * a rule enforced on one of two is not enforced.
 */
class ProductAdminServiceTest {

    private val gold = TierId(1)
    private val silver = TierId(2)

    private fun variant(sku: String, value: String?, active: Boolean = true) = ProductVariant(
        id = sku.hashCode().toLong(),
        sku = SkuCode(sku),
        variantValue = value,
        packQuantity = PackQuantity(1),
        mapPrice = null,
        upc = null,
        weight = null,
        sortOrder = 0,
        active = active,
        stock = StockLevel(5, 0, Instant.EPOCH),
    )

    private fun product(
        visibility: ProductVisibility = ProductVisibility.HIDDEN,
        variants: List<ProductVariant> = listOf(variant("PL-1-S", "S")),
        categoryIds: List<Long> = listOf(2),
        primaryCategoryId: Long? = 2,
    ) = Product(
        id = 1,
        spuCode = SpuCode("PL-1"),
        name = "Chrome Hub Cap",
        brand = null,
        description = null,
        baseWholesalePrice = Money.of("10.00"),
        locationCode = null,
        variantAxis = VariantAxis.SIZE,
        attributes = emptyMap(),
        visibility = visibility,
        categoryIds = categoryIds,
        primaryCategoryId = primaryCategoryId,
        imageUrls = emptyList(),
        variants = variants,
    )

    private fun service(
        products: InMemoryProductRepository,
        tierPrices: InMemoryTierPriceRepository = InMemoryTierPriceRepository(),
        categories: InMemoryCategoryRepository = InMemoryCategoryRepository(
            listOf(Category(1, "Truck", "truck", null, 0), Category(2, "Hub Caps", "hub-caps", 1, 0))
        ),
        stock: InMemoryStockBreakdownRepository = InMemoryStockBreakdownRepository(),
    ) = ProductAdminService(
        products,
        tierPrices,
        categories,
        InMemoryTierRepository(listOf(CustomerTier(gold, "Gold", 1), CustomerTier(silver, "Silver", 2))),
        stock,
    )

    private fun priced(sku: String) = TierPrice(SkuCode(sku), gold, Money.of("4.00"), Quantity(1))

    private fun command(
        visibility: String = "HIDDEN",
        categoryIds: List<Long> = listOf(2),
        primaryCategoryId: Long? = 2,
        tierPrices: List<TierPriceEntry> = emptyList(),
        variantMapPrices: Map<Long, BigDecimal?> = emptyMap(),
    ) = UpdateProductCommand(
        baseWholesalePrice = BigDecimal("12.00"),
        locationCode = "A1-1",
        visibility = visibility,
        categoryIds = categoryIds,
        primaryCategoryId = primaryCategoryId,
        tierPrices = tierPrices,
        variantMapPrices = variantMapPrices,
    )

    // ─── The pricing guard, on both routes ───────────────────────────────

    @Test
    fun `an unpriced product cannot be made visible from the eye button`() {
        val products = InMemoryProductRepository(listOf(product()))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products).setActive(1, active = true)
        }

        assertTrue(failure.message!!.contains("PL-1"), failure.message)
        assertEquals(ProductVisibility.HIDDEN, products.findById(1)!!.visibility)
    }

    @Test
    fun `an unpriced product cannot be made visible from the edit form either`() {
        val products = InMemoryProductRepository(listOf(product()))

        assertFailsWith<UseCaseViolation> {
            service(products).update(1, command(visibility = "VISIBLE"))
        }
    }

    @Test
    fun `pricing every SKU in the same save is allowed`() {
        val products = InMemoryProductRepository(listOf(product()))
        val service = service(products)

        // Which is why the check runs after the price book is written.
        val result = service.update(
            1,
            command(
                visibility = "VISIBLE",
                tierPrices = listOf(TierPriceEntry("PL-1-S", gold.value, BigDecimal("4.00"))),
            ),
        )

        assertEquals("VISIBLE", result.product.visibility)
        assertEquals(ProductVisibility.VISIBLE, products.findById(1)!!.visibility)
    }

    @Test
    fun `a priced product goes visible from the eye button`() {
        val products = InMemoryProductRepository(listOf(product()))
        val service = service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))

        val result = service.setActive(1, active = true)

        assertEquals("VISIBLE", result.product.visibility)
    }

    @Test
    fun `half a price book is not enough`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M"))))
        )
        // One size priced and the other at nothing reads as a bug, not a missing price.
        val service = service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))

        assertFailsWith<UseCaseViolation> { service.setActive(1, active = true) }
    }

    @Test
    fun `a withdrawn SKU does not have to be priced`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M", active = false))))
        )
        // Holding the product back over a SKU nobody can buy holds it back forever.
        val service = service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))

        assertEquals("VISIBLE", service.setActive(1, active = true).product.visibility)
    }

    @Test
    fun `a product with nothing left on sale cannot be visible however well priced`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S", active = false))))
        )
        val service = service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))

        assertFailsWith<UseCaseViolation> { service.setActive(1, active = true) }
    }

    @Test
    fun `hiding is always allowed`() {
        val products = InMemoryProductRepository(listOf(product(visibility = ProductVisibility.VISIBLE)))

        val result = service(products).setActive(1, active = false)

        assertEquals("HIDDEN", result.product.visibility)
    }

    @Test
    fun `asking for the state it is already in changes nothing`() {
        val products = InMemoryProductRepository(listOf(product(visibility = ProductVisibility.VISIBLE)))

        // No price book, so the guard would refuse — and must not run at all here.
        val result = service(products).setActive(1, active = true)

        assertEquals("VISIBLE", result.product.visibility)
    }

    // ─── Categories ──────────────────────────────────────────────────────

    @Test
    fun `an unknown category is refused`() {
        val products = InMemoryProductRepository(listOf(product()))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products).update(1, command(categoryIds = listOf(99), primaryCategoryId = 99))
        }

        assertTrue(failure.message!!.contains("99"), failure.message)
    }

    @Test
    fun `the primary category must be one of the assigned ones`() {
        val products = InMemoryProductRepository(listOf(product()))

        assertFailsWith<UseCaseViolation> {
            service(products).update(1, command(categoryIds = listOf(2), primaryCategoryId = 1))
        }
    }

    @Test
    fun `filing under nothing is allowed`() {
        val products = InMemoryProductRepository(listOf(product()))

        val result = service(products)
            .update(1, command(categoryIds = emptyList(), primaryCategoryId = null))

        assertTrue(result.product.categories.isEmpty())
    }

    // ─── What an update may and may not reach ────────────────────────────

    @Test
    fun `the ERP's fields survive an update`() {
        val products = InMemoryProductRepository(listOf(product()))

        service(products).update(1, command())

        val saved = products.findById(1)!!
        assertEquals("Chrome Hub Cap", saved.name)
        assertEquals(SpuCode("PL-1"), saved.spuCode)
        assertEquals(VariantAxis.SIZE, saved.variantAxis)
        // And the portal's fields did change, so this is not passing vacuously.
        assertEquals(Money.of("12.00"), saved.baseWholesalePrice)
        assertEquals("A1-1", saved.locationCode)
    }

    @Test
    fun `an absent MAP entry leaves the SKU's MAP alone`() {
        val withMap = variant("PL-1-S", "S").let {
            ProductVariant(
                it.id, it.sku, it.variantValue, it.packQuantity, Money.of("19.99"),
                it.upc, it.weight, it.sortOrder, it.active, it.stock,
            )
        }
        val products = InMemoryProductRepository(listOf(product(variants = listOf(withMap))))

        // A partial form submission must not wipe what it did not mention.
        service(products).update(1, command(variantMapPrices = emptyMap()))

        assertEquals(Money.of("19.99"), products.findById(1)!!.variants.single().mapPrice)
    }

    @Test
    fun `an explicit null clears the MAP`() {
        val withMap = variant("PL-1-S", "S").let {
            ProductVariant(
                it.id, it.sku, it.variantValue, it.packQuantity, Money.of("19.99"),
                it.upc, it.weight, it.sortOrder, it.active, it.stock,
            )
        }
        val products = InMemoryProductRepository(listOf(product(variants = listOf(withMap))))

        service(products).update(1, command(variantMapPrices = mapOf(withMap.id!! to null)))

        assertNull(products.findById(1)!!.variants.single().mapPrice)
    }

    @Test
    fun `an update refuses a SKU that is not this product's`() {
        val products = InMemoryProductRepository(listOf(product()))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products).update(
                1,
                command(tierPrices = listOf(TierPriceEntry("SOMETHING-ELSE", gold.value, BigDecimal("4.00")))),
            )
        }

        assertTrue(failure.message!!.contains("SOMETHING-ELSE"), failure.message)
    }

    @Test
    fun `an update refuses an unknown tier`() {
        val products = InMemoryProductRepository(listOf(product()))

        assertFailsWith<UseCaseViolation> {
            service(products).update(1, command(tierPrices = listOf(TierPriceEntry("PL-1-S", 99, BigDecimal("4.00")))))
        }
    }

    @Test
    fun `saving a price book replaces only the SKUs it mentions`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M"))))
        )
        val prices = InMemoryTierPriceRepository(listOf(priced("PL-1-S"), priced("PL-1-M")))

        service(products, prices).update(
            1,
            command(tierPrices = listOf(TierPriceEntry("PL-1-S", gold.value, BigDecimal("9.99")))),
        )

        assertEquals(Money.of("9.99"), prices.all.single { it.sku == SkuCode("PL-1-S") }.price)
        assertEquals(Money.of("4.00"), prices.all.single { it.sku == SkuCode("PL-1-M") }.price)
    }

    // ─── Reads ───────────────────────────────────────────────────────────

    @Test
    fun `the detail view reports whether the product could be sold`() {
        val products = InMemoryProductRepository(listOf(product()))

        assertFalse(service(products).findById(1)!!.product.sellable!!)
        assertTrue(
            service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))
                .findById(1)!!.product.sellable!!
        )
    }

    @Test
    fun `the detail view carries the price book and the stock breakdown`() {
        val products = InMemoryProductRepository(listOf(product()))
        val lines = listOf(
            WarehouseStockLine("PL-1-S", 35751, "TX", 40, 300, Instant.EPOCH),
            WarehouseStockLine("PL-1-S", 35809, "TN", 2, 0, Instant.EPOCH),
        )

        val detail = service(
            products,
            InMemoryTierPriceRepository(listOf(priced("PL-1-S"))),
            stock = InMemoryStockBreakdownRepository(lines),
        ).findById(1)!!

        assertEquals(1, detail.tierPrices.size)
        assertEquals(listOf("TX", "TN"), detail.stockByWarehouse.map { it.warehouseName })
        assertEquals(300, detail.stockByWarehouse.first().incoming)
    }

    @Test
    fun `a warehouse that has left the registry still names itself`() {
        val products = InMemoryProductRepository(listOf(product()))
        val orphan = listOf(WarehouseStockLine("PL-1-S", 4242, "", 7, 0, Instant.EPOCH))

        val detail = service(products, stock = InMemoryStockBreakdownRepository(orphan)).findById(1)!!

        assertEquals("Warehouse 4242", detail.stockByWarehouse.single().warehouseName)
    }

    @Test
    fun `a missing product reads as absent rather than as an error`() {
        assertNull(service(InMemoryProductRepository()).findById(404))
    }

    @Test
    fun `an unknown id fails loudly on write`() {
        assertFailsWith<NoSuchElementException> {
            service(InMemoryProductRepository()).setActive(404, active = false)
        }
    }

    @Test
    fun `an unparseable visibility is refused`() {
        val products = InMemoryProductRepository(listOf(product()))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products).update(1, command(visibility = "SORT-OF-VISIBLE"))
        }

        assertTrue(failure.message!!.contains("SORT-OF-VISIBLE"), failure.message)
    }
}
