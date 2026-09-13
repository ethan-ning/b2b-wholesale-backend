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
    private val default = TierId(3)

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
        tierPrices: InMemoryTierPriceRepository = allPriced(),
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

    /** A SKU's default price. Every other tier is worked out from it. */
    private fun priced(sku: String, price: String = "10.00") =
        TierPrice(SkuCode(sku), default, Money.of(price), Quantity(1))

    /** A price stated for one tier, departing from that SKU's default. */
    private fun stated(sku: String, tier: TierId, price: String) =
        TierPrice(SkuCode(sku), tier, Money.of(price), Quantity(1))

    /** The usual case: every SKU of the fixture product has a default price. */
    private fun allPriced(vararg skus: String = arrayOf("PL-1-S")) =
        InMemoryTierPriceRepository(skus.map { priced(it) })

    private fun command(
        visibility: String = "HIDDEN",
        categoryIds: List<Long> = listOf(2),
        primaryCategoryId: Long? = 2,
        tierPrices: List<TierPriceEntry> = emptyList(),
        variantMapPrices: Map<Long, BigDecimal?> = emptyMap(),
    ) = UpdateProductCommand(
        locationCode = "A1-1",
        visibility = visibility,
        categoryIds = categoryIds,
        primaryCategoryId = primaryCategoryId,
        tierPrices = tierPrices,
        variantMapPrices = variantMapPrices,
    )

    // ─── The pricing guard, on both routes ───────────────────────────────

    @Test
    fun `a product with an unpriced SKU cannot be made visible from the eye button`() {
        val products = InMemoryProductRepository(listOf(product()))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products, InMemoryTierPriceRepository()).setActive(1, active = true)
        }

        assertTrue(failure.message!!.contains("PL-1"), failure.message)
        assertEquals(ProductVisibility.HIDDEN, products.findById(1)!!.visibility)
    }

    /**
     * The two ways a product can be unshowable are unrelated, and saying so in one
     * sentence sent someone hunting for a per-SKU price field that does not exist. Each
     * refusal now names its own cause.
     */
    @Test
    fun `a product whose SKUs are all discontinued says so, and does not mention pricing`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S", active = false))))
        )

        val failure = assertFailsWith<UseCaseViolation> {
            service(products).setActive(1, active = true)
        }

        assertTrue(failure.message!!.contains("discontinued"), failure.message)
        // The base price is fine, so nothing should point at prices.
        assertFalse(failure.message!!.contains("price"), failure.message)
    }

    @Test
    fun `a product with an unpriced SKU says that, and does not mention SKUs being on sale`() {
        val products = InMemoryProductRepository(listOf(product()))

        val failure = assertFailsWith<UseCaseViolation> {
            service(products, InMemoryTierPriceRepository()).setActive(1, active = true)
        }

        assertTrue(failure.message!!.contains("default price"), failure.message)
        assertFalse(failure.message!!.contains("discontinued"), failure.message)
    }

    @Test
    fun `a product nobody has priced per SKU goes visible on its tier discounts alone`() {
        val products = InMemoryProductRepository(listOf(product()))

        service(products).setActive(1, active = true)

        assertEquals(ProductVisibility.VISIBLE, products.findById(1)!!.visibility)
    }

    @Test
    fun `a product with an unpriced SKU cannot be made visible from the edit form either`() {
        val products = InMemoryProductRepository(listOf(product()))

        assertFailsWith<UseCaseViolation> {
            service(products, InMemoryTierPriceRepository())
                .update(1, command(visibility = "VISIBLE"))
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
                tierPrices = listOf(
                    TierPriceEntry("PL-1-S", default.value, BigDecimal("10.00")),
                    TierPriceEntry("PL-1-S", gold.value, BigDecimal("4.00")),
                ),
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
    fun `pricing one size and not the other blocks the product`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M"))))
        )
        // The unpriced size has no price at any tier, not a cheap one.
        val service = service(products, allPriced("PL-1-S"))

        assertFailsWith<UseCaseViolation> { service.setActive(1, active = true) }
    }

    @Test
    fun `a withdrawn SKU does not have to be priced`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M", active = false))))
        )
        // Holding the product back over a SKU nobody can buy holds it back forever.
        val service = service(products, allPriced("PL-1-S"))

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
        assertEquals("A1-1", saved.locationCode)
    }

    /**
     * The search figure follows the prices rather than being typed. Left behind, a price
     * filter would sort a catalogue on numbers nobody had set in months.
     */
    @Test
    fun `the reference price becomes the cheapest default price on sale`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(variant("PL-1-S", "S"), variant("PL-1-M", "M"))))
        )
        val prices = InMemoryTierPriceRepository(
            listOf(priced("PL-1-S", "18.00"), priced("PL-1-M", "11.00")),
        )

        service(products, prices).update(1, command())

        assertEquals(Money.of("11.00"), products.findById(1)!!.baseWholesalePrice)
    }

    @Test
    fun `a withdrawn SKU's price does not set the reference`() {
        val products = InMemoryProductRepository(
            listOf(product(variants = listOf(
                variant("PL-1-S", "S"),
                variant("PL-1-M", "M", active = false),
            )))
        )
        val prices = InMemoryTierPriceRepository(
            listOf(priced("PL-1-S", "18.00"), priced("PL-1-M", "1.00")),
        )

        service(products, prices).update(1, command())

        // The cheap one cannot be bought, so pricing the product from it would mislead.
        assertEquals(Money.of("18.00"), products.findById(1)!!.baseWholesalePrice)
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
            command(tierPrices = listOf(
                TierPriceEntry("PL-1-S", default.value, BigDecimal("10.00")),
                TierPriceEntry("PL-1-S", gold.value, BigDecimal("9.99")),
            )),
        )

        // The SKU named in the save is rewritten; the one not named is left alone.
        assertEquals(Money.of("9.99"), prices.all.single { it.sku == SkuCode("PL-1-S") && it.tierId == gold }.price)
        assertEquals(Money.of("10.00"), prices.all.single { it.sku == SkuCode("PL-1-M") }.price)
    }

    // ─── Reads ───────────────────────────────────────────────────────────

    @Test
    fun `the detail view reports whether the product could be sold`() {
        assertTrue(service(InMemoryProductRepository(listOf(product()))).findById(1)!!.product.sellable!!)
        assertFalse(
            service(InMemoryProductRepository(listOf(product())), InMemoryTierPriceRepository())
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

        val book = service(
            products,
            InMemoryTierPriceRepository(
                listOf(priced("PL-1-S"), priced("PL-1-M"), stated("PL-1-S", gold, "7.00")),
            ),
        ).findById(1)!!.tierPrices

        // Two SKUs against three tiers.
        assertEquals(6, book.size)

        val derived = book.single { it.sku == "PL-1-M" && it.tierName == "Gold" }
        assertFalse(derived.customised)
        // A default price of 10.00, 18% off.
        assertEquals(Money.of("8.20").amount, derived.price)
        assertEquals(Money.of("8.20").amount, derived.standardPrice)

        val statedRow = book.single { it.sku == "PL-1-S" && it.tierId == gold.value }
        assertTrue(statedRow.customised)
        assertEquals(Money.of("7.00").amount, statedRow.price)
        // What it departed from is still reported beside it.
        assertEquals(Money.of("8.20").amount, statedRow.standardPrice)

        // The anchor states its own price and has nothing to depart from.
        val anchorRow = book.single { it.sku == "PL-1-M" && it.tierName == "Default" }
        assertTrue(anchorRow.anchor)
        assertEquals(Money.of("10.00").amount, anchorRow.price)
        assertNull(anchorRow.standardPrice)
    }

    @Test
    fun `the anchor tier states its price rather than discounting one`() {
        val products = InMemoryProductRepository(listOf(product()))

        val row = service(products).findById(1)!!.tierPrices.single { it.tierName == "Default" }

        assertTrue(row.anchor)
        assertEquals(Money.of("10.00").amount, row.price)
        assertNull(row.standardPrice)
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
