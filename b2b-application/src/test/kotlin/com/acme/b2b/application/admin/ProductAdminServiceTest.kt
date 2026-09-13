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
        basePrice: String = "10.00",
        variants: List<ProductVariant> = listOf(variant("PL-1-S", "S")),
        categoryIds: List<Long> = listOf(2),
        primaryCategoryId: Long? = 2,
    ) = Product(
        id = 1,
        spuCode = SpuCode("PL-1"),
        name = "Chrome Hub Cap",
        brand = null,
        description = null,
        baseWholesalePrice = Money.of(basePrice),
        locationCode = null,
        variantAxis = VariantAxis.SIZE,
        attributes = emptyMap(),
        visibility = visibility,
        categoryIds = categoryIds,
        primaryCategoryId = primaryCategoryId,
        images = emptyList(),
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
        InMemoryTierRepository(),
        stock,
    )

    private fun priced(sku: String) = TierPrice(SkuCode(sku), gold, Money.of("4.00"), Quantity(1))

    private fun command(
        visibility: String = "HIDDEN",
        baseWholesalePrice: BigDecimal = BigDecimal("12.00"),
        categoryIds: List<Long> = listOf(2),
        primaryCategoryId: Long? = 2,
        tierPrices: List<TierPriceEntry> = emptyList(),
        variantMapPrices: Map<Long, BigDecimal?> = emptyMap(),
    ) = UpdateProductCommand(
        baseWholesalePrice = baseWholesalePrice,
        locationCode = "A1-1",
        visibility = visibility,
        categoryIds = categoryIds,
        primaryCategoryId = primaryCategoryId,
        tierPrices = tierPrices,
        variantMapPrices = variantMapPrices,
    )

    // ─── The pricing guard, on both routes ───────────────────────────────

    @Test
    fun `a product with no list price cannot be made visible from the eye button`() {
        // Nothing has to be priced per SKU any more — tiers carry a discount — but a
        // discount off nothing is nothing, and visible this would be offered free.
        val products = InMemoryProductRepository(listOf(product(basePrice = "0.00")))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products).setActive(1, active = true)
        }

        assertTrue(failure.message!!.contains("PL-1"), failure.message)
        assertEquals(ProductVisibility.HIDDEN, products.findById(1)!!.visibility)
    }

    @Test
    fun `a product nobody has priced per SKU goes visible on its tier discounts alone`() {
        val products = InMemoryProductRepository(listOf(product()))

        service(products).setActive(1, active = true)

        assertEquals(ProductVisibility.VISIBLE, products.findById(1)!!.visibility)
    }

    @Test
    fun `a product with no list price cannot be made visible from the edit form either`() {
        val products = InMemoryProductRepository(listOf(product(basePrice = "0.00")))

        assertFailsWith<UseCaseViolation> {
            service(products).update(1, command(visibility = "VISIBLE", baseWholesalePrice = BigDecimal.ZERO))
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
    fun `pricing one size and not the other is no longer a problem`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M"))))
        )
        // It used to block the product. The unpriced size now takes its tier's rate.
        val service = service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))

        service.setActive(1, active = true)

        assertEquals(ProductVisibility.VISIBLE, products.findById(1)!!.visibility)
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
        assertTrue(service(InMemoryProductRepository(listOf(product()))).findById(1)!!.product.sellable!!)
        assertFalse(
            service(InMemoryProductRepository(listOf(product(basePrice = "0.00"))))
                .findById(1)!!.product.sellable!!
        )
    }

    /**
     * A row for every SKU against every tier, whether or not anyone typed a price. The
     * screen renders all of them, and building the list here keeps one place deciding
     * what a tier pays.
     */
    @Test
    fun `the price book covers every SKU and tier, saying which prices were set by hand`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M"))))
        )

        val book = service(products, InMemoryTierPriceRepository(listOf(priced("PL-1-S"))))
            .findById(1)!!.tierPrices

        // Two SKUs against three tiers.
        assertEquals(6, book.size)

        val standard = book.single { it.sku == "PL-1-M" && it.tierName == "Gold" }
        assertFalse(standard.customised)
        // 10.00 list, 18% off.
        assertEquals(Money.of("8.20").amount, standard.price)
        assertEquals(Money.of("8.20").amount, standard.standardPrice)

        val overridden = book.single { it.sku == "PL-1-S" && it.tierId == 1L }
        assertTrue(overridden.customised)
        // The override stands, and what it departed from is still reported beside it.
        assertEquals(Money.of("8.20").amount, overridden.standardPrice)
    }

    @Test
    fun `a tier paying list is not treated as a discount`() {
        val products = InMemoryProductRepository(listOf(product()))

        val row = service(products).findById(1)!!.tierPrices.single { it.tierName == "Default" }

        assertEquals(Money.of("10.00").amount, row.price)
        assertEquals(java.math.BigDecimal("0.00"), row.discountPercent)
        assertFalse(row.customised)
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

        // One SKU against the three tiers.
        assertEquals(3, detail.tierPrices.size)
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
