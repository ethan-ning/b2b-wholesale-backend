package com.acme.b2b.application.admin

import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUserRepository
import com.acme.b2b.application.support.AdminContext
import com.acme.b2b.domain.auth.AccessTokenIssuer
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.auth.TemporaryPasswordGenerator
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.catalog.*
import com.acme.b2b.domain.customer.*
import com.acme.b2b.domain.pricing.TierPrice
import com.acme.b2b.domain.pricing.TierPriceRepository
import com.acme.b2b.types.*

/**
 * In-memory stand-ins for the ports. Hand-written rather than mocked: they behave like
 * the real thing (a save assigns an id, a duplicate email is visible), so the tests
 * exercise the use case rather than asserting on call sequences.
 *
 * That these exist at all is the payoff of the port interfaces — no database, no Spring
 * context, no mocking framework.
 */

class InMemoryCustomerRepository(seed: List<Customer> = emptyList()) : CustomerRepository {
    private val rows = mutableMapOf<Long, Customer>()
    private var nextId = 1L

    init { seed.forEach { save(it) } }

    override fun findById(id: Long) = rows[id]
    override fun findByEmail(email: Email) = rows.values.firstOrNull { it.email == email }
    override fun existsByEmail(email: Email) = findByEmail(email) != null
    override fun anyOnTier(tierId: TierId) = rows.values.any { it.tierId == tierId }
    override fun countAll() = rows.size.toLong()
    override fun countByStatus(status: CustomerStatus) = rows.values.count { it.status == status }.toLong()

    override fun search(criteria: CustomerSearchCriteria, page: Page): PageOf<Customer> {
        val text = criteria.text?.lowercase()
        val matched = rows.values
            .filter { criteria.status == null || it.status == criteria.status }
            .filter {
                text == null ||
                    it.name.lowercase().contains(text) ||
                    it.email.value.contains(text) ||
                    it.companyName.lowercase().contains(text)
            }
            .sortedBy { it.id }
        return PageOf.of(matched, page)
    }

    override fun save(customer: Customer): Customer {
        val id = customer.id ?: nextId++
        val stored = Customer(
            id, customer.email, customer.passwordHash, customer.name, customer.companyName,
            customer.tierId, customer.phone, customer.status, customer.mustChangePassword,
            customer.createdAt,
        )
        rows[id] = stored
        return stored
    }
}

class InMemoryTierRepository(
    private val tiers: List<CustomerTier> = listOf(
        CustomerTier(TierId(1), "Gold", 1),
        CustomerTier(TierId(2), "Silver", 2),
    ),
) : CustomerTierRepository {
    override fun findById(id: TierId) = tiers.firstOrNull { it.id == id }
    override fun findAll() = tiers
}

class InMemoryAdminRepository(seed: List<AdminUser> = emptyList()) : AdminUserRepository {
    private val admins = seed.toMutableList()
    private var nextId = (seed.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1

    override fun findByEmail(email: Email) = admins.firstOrNull { it.email == email }
    override fun findById(id: Long) = admins.firstOrNull { it.id == id }
    override fun findAll(): List<AdminUser> = admins.toList()
    override fun existsByEmail(email: Email) = admins.any { it.email == email }
    override fun countByRole(role: AdminRole) = admins.count { it.role == role }.toLong()
    override fun deleteById(id: Long) { admins.removeIf { it.id == id } }

    override fun save(admin: AdminUser): AdminUser {
        val id = admin.id ?: nextId++
        val stored = AdminUser(
            id, admin.email, admin.passwordHash, admin.name, admin.role, admin.mustChangePassword,
        )
        admins.removeIf { it.id == id }
        admins += stored
        return stored
    }
}

class FixedAdminContext(private val id: Long?) : AdminContext {
    override fun currentAdminId() = id
}

/** Reversible stand-in for BCrypt — fast, and lets a test assert on what was hashed. */
class FakePasswordHasher : PasswordHasher {
    override fun hash(raw: RawPassword) = PasswordHash("hashed:${raw.value}")
    override fun matches(raw: RawPassword, hash: PasswordHash) = hash.value == "hashed:${raw.value}"
}

class FixedTemporaryPasswordGenerator(private val value: String = "TempPass1234") : TemporaryPasswordGenerator {
    override fun generate() = RawPassword(value)
}

class FakeTokenIssuer : AccessTokenIssuer {
    override fun issueForAdmin(adminId: Long, email: String, role: String) = "admin-token:$adminId:$role"
    override fun issueForDealer(customerId: Long, email: String, tierId: Long) = "dealer-token:$customerId:$tierId"
    override fun issuePasswordChangeToken(customerId: Long, email: String) = "pwchange-token:$customerId"
    override fun issueAdminPasswordChangeToken(adminId: Long, email: String) = "admin-pwchange-token:$adminId"
}

/**
 * Holds the tree as a flat list of rows, the way the table does, and derives the nested
 * shape on read — so a test that reparents a node cannot forget to fix the other copy.
 */
class InMemoryCategoryRepository(seed: List<Category> = emptyList()) : CategoryRepository {
    private val rows = mutableMapOf<Long, Category>()
    private var nextId = 1L

    /** categoryId -> product ids, standing in for the product_category join table. */
    val filings = mutableMapOf<Long, MutableSet<Long>>()

    init { seed.forEach { save(it) } }

    override fun findById(id: Long) = rows[id]

    override fun findTree(): List<Category> {
        fun build(parentId: Long?): List<Category> =
            rows.values.filter { it.parentId == parentId }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { it.copy(children = build(it.id)) }
        return build(null)
    }

    override fun findDescendantIds(categoryId: Long): List<Long> =
        rows.values.filter { it.parentId == categoryId }
            .flatMap { child -> listOfNotNull(child.id) + child.id!!.let { findDescendantIds(it) } }

    override fun hasChildren(categoryId: Long) = rows.values.any { it.parentId == categoryId }

    override fun depthOf(categoryId: Long): Int {
        var depth = 1
        var cursor = rows[categoryId]?.parentId
        while (cursor != null) { depth++; cursor = rows[cursor]?.parentId }
        return depth
    }

    override fun productIdsByCategory(): Map<Long, Set<Long>> = filings.mapValues { it.value.toSet() }

    override fun existsBySlug(slug: String) = rows.values.any { it.slug == slug }

    override fun save(category: Category): Category {
        val id = category.id ?: nextId++
        val stored = category.copy(id = id, children = emptyList())
        rows[id] = stored
        // Seeded rows carry their own ids; a later insert must not land on one of them.
        nextId = maxOf(nextId, id + 1)
        return stored
    }

    override fun deleteById(id: Long) { rows.remove(id); filings.remove(id) }
}

/**
 * Only what a category delete needs: find what is filed under a node, and take the save
 * that unfiles it. The rest of the port throws, so a test that starts leaning on it says
 * so loudly instead of passing against a silent stub.
 */
class InMemoryProductRepository(seed: List<Product> = emptyList()) : ProductRepository {
    private val rows = mutableMapOf<Long, Product>()

    init { seed.forEach { save(it) } }

    val all: List<Product> get() = rows.values.toList()

    override fun findById(id: Long) = rows[id]
    override fun findBySpuCode(spuCode: SpuCode) = rows.values.firstOrNull { it.spuCode == spuCode }

    override fun findByCategoryId(categoryId: Long) =
        rows.values.filter { categoryId in it.categoryIds }

    override fun save(product: Product): Product {
        val id = requireNotNull(product.id) { "the fake does not assign ids" }
        rows[id] = product
        return product
    }


    override fun search(criteria: ProductSearchCriteria, page: Page) = throw NotImplementedError()
    override fun countAll() = rows.size.toLong()
    override fun countByVisibility(visibility: ProductVisibility) = rows.values.count { it.visibility == visibility }.toLong()
}

class InMemoryTierPriceRepository(seed: List<TierPrice> = emptyList()) : TierPriceRepository {
    private val rows = mutableListOf<TierPrice>()

    init { rows += seed }

    val all: List<TierPrice> get() = rows.toList()

    override fun findAllFor(skus: Collection<SkuCode>) = rows.filter { it.sku in skus }

    override fun findFor(skus: Collection<SkuCode>, tierId: TierId) =
        rows.filter { it.sku in skus && it.tierId == tierId }

    override fun replaceFor(sku: SkuCode, prices: List<TierPrice>) {
        rows.removeAll { it.sku == sku }
        rows += prices
    }
}

/** Where stock sits, for the one admin view that asks. Empty unless a test seeds it. */
class InMemoryStockBreakdownRepository(
    private val lines: List<WarehouseStockLine> = emptyList(),
) : VariantStockBreakdownRepository {
    override fun findBySkus(skus: List<String>) = lines.filter { it.sku in skus }
}

/** A product with one SKU whose id is known, for the image rules that turn on SKU identity. */
fun productWith(id: Long, spuCode: String, variantId: Long = 11) = Product(
    id = id,
    spuCode = SpuCode(spuCode),
    name = "Product $spuCode",
    brand = null,
    description = null,
    baseWholesalePrice = Money.of("10.00"),
    locationCode = null,
    variantAxis = null,
    attributes = emptyMap(),
    visibility = ProductVisibility.VISIBLE,
    categoryIds = emptyList(),
    primaryCategoryId = null,
    images = emptyList(),
    variants = listOf(
        ProductVariant(
            id = variantId,
            sku = SkuCode("$spuCode-A"),
            variantValue = null,
            packQuantity = PackQuantity(1),
            mapPrice = null,
            upc = null,
            weight = null,
            sortOrder = 0,
            active = true,
            stock = StockLevel(5, 0, java.time.Instant.EPOCH),
        )
    ),
)

class InMemoryImageRepository : ImageRepository {
    private val rows = mutableMapOf<Long, Image>()
    private var nextId = 1L
    /** Set by the gallery fake, so usage can be answered without a database. */
    var attachments: () -> Map<Long, List<UsedBy>> = { emptyMap() }

    override fun findById(id: Long) = rows[id]
    override fun findByObjectKey(objectKey: String) = rows.values.firstOrNull { it.objectKey == objectKey }
    override fun deleteById(id: Long) { rows.remove(id) }

    override fun save(image: Image): Image {
        val id = image.id ?: nextId++
        val stored = image.copy(id = id, uploadedAt = image.uploadedAt ?: java.time.Instant.EPOCH)
        rows[id] = stored
        return stored
    }

    override fun findPageWithUsage(search: ImageSearch, page: Page): PageOf<ImageUsage> {
        val usage = attachments()
        val term = search.term?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val all = rows.values
            .sortedByDescending { it.id }
            .map { ImageUsage(it, usage[it.id].orEmpty()) }
            .filter { !search.unusedOnly || it.products.isEmpty() }
            .filter { row ->
                term == null
                    || row.image.filename.lowercase().contains(term)
                    || row.products.any { "${it.spuCode} ${it.name}".lowercase().contains(term) }
            }
        return PageOf.of(all, page)
    }

    override fun countUnused(): Long {
        val usage = attachments()
        return rows.values.count { usage[it.id].isNullOrEmpty() }.toLong()
    }

    override fun usageOf(imageId: Long): List<UsedBy> = attachments()[imageId].orEmpty()
}

class InMemoryProductImageRepository(
    private val images: InMemoryImageRepository,
    private val products: InMemoryProductRepository = InMemoryProductRepository(),
) : ProductImageRepository {
    /** productId -> image ids, in gallery order. */
    private val galleries = mutableMapOf<Long, MutableList<Long>>()
    private val mainImages = mutableMapOf<Long, Long?>()

    init {
        images.attachments = {
            galleries.flatMap { (productId, ids) ->
                val product = products.findById(productId)
                val usedBy = UsedBy(productId, product?.spuCode?.value ?: "", product?.name ?: "")
                ids.map { it to usedBy }
            }.groupBy({ it.first }, { it.second })
        }
    }

    fun mainImageOf(variantId: Long): Long? = mainImages[variantId]

    override fun imagesOf(productId: Long) = galleries[productId].orEmpty().mapNotNull { images.findById(it) }
    override fun countFor(productId: Long) = galleries[productId].orEmpty().size
    override fun isAttached(productId: Long, imageId: Long) = imageId in galleries[productId].orEmpty()
    override fun attach(productId: Long, imageId: Long) { galleries.getOrPut(productId) { mutableListOf() } += imageId }
    override fun detach(productId: Long, imageId: Long) { galleries[productId]?.remove(imageId) }

    override fun reorder(productId: Long, imageIdsInOrder: List<Long>) {
        galleries[productId] = imageIdsInOrder.toMutableList()
    }

    override fun setMainImage(variantId: Long, imageId: Long?) { mainImages[variantId] = imageId }

    override fun clearMainImage(productId: Long, imageId: Long) {
        mainImages.keys.filter { mainImages[it] == imageId }.forEach { mainImages[it] = null }
    }
}
