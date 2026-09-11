package com.acme.b2b.application.admin

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.Image
import com.acme.b2b.domain.catalog.ImageRules
import com.acme.b2b.domain.catalog.ImageStore
import com.acme.b2b.domain.catalog.StoredObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A one-pixel PNG. Only its bytes matter here; nothing in this layer decodes them. */
private val PNG = java.util.Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

/**
 * Stands in for the bucket. It reports a pixel size the way a real store does — reading it
 * belongs to the adapter, so the sizes here are arbitrary and deliberately not the PNG's:
 * the use case must pass on what the store said, never something it worked out itself.
 */
class RecordingImageStore : ImageStore {
    val stored = mutableMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()
    private var next = 0

    /** Null for one upload, to exercise a format the runtime cannot measure. */
    var reportedSize: Pair<Int, Int>? = 640 to 480

    override fun put(content: ByteArray, contentType: String, filename: String): StoredObject {
        val key = "key-${next++}"
        stored[key] = content
        return StoredObject(key, "https://example.test/$key", reportedSize?.first, reportedSize?.second)
    }

    override fun delete(objectKey: String) { deleted += objectKey }
}

class ImageAdminServiceTest {

    private val images = InMemoryImageRepository()
    private val products = InMemoryProductRepository(listOf(productWith(id = 1, spuCode = "SPU-1")))
    private val galleries = InMemoryProductImageRepository(images, products)
    private val store = RecordingImageStore()
    private val service = ImageAdminService(images, galleries, products, store)

    private fun upload(name: String = "part.png") = service.upload(PNG, "image/png", name)

    // ---- uploading --------------------------------------------------------------------

    @Test
    fun `an upload records what the file is, not what the client claimed`() {
        val created = upload("hubcap.png")

        assertEquals("hubcap.png", created.filename)
        assertEquals("image/png", created.contentType)
        // Counted from the bytes, which is the only source that cannot be wrong.
        assertEquals(PNG.size.toLong(), created.bytes)
        assertTrue(created.stored)
    }

    /** ImageDimensionsTest covers the reading itself; this covers it arriving intact. */
    @Test
    fun `the pixel size comes from the store, not from this layer`() {
        store.reportedSize = 1200 to 800

        val created = upload()

        assertEquals(1200, created.width)
        assertEquals(800, created.height)
    }

    @Test
    fun `a format the store could not measure is still recorded`() {
        store.reportedSize = null

        val created = service.upload(PNG, "image/webp", "wheel.webp")

        assertNull(created.width)
        assertNull(created.height)
        assertTrue(created.stored)
    }

    @Test
    fun `a format we cannot serve is refused, and says what was sent`() {
        val failure = assertFailsWith<UseCaseViolation> { service.upload(PNG, "application/pdf", "spec.pdf") }

        assertTrue(failure.message!!.contains("pdf"))
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun `a file with no type at all is refused rather than guessed at`() {
        assertFailsWith<UseCaseViolation> { service.upload(PNG, null, "mystery") }
    }

    @Test
    fun `a charset on the content type does not make it unrecognisable`() {
        val created = service.upload(PNG, "image/png; charset=binary", "x.png")

        assertEquals("image/png", created.contentType)
    }

    @Test
    fun `an empty file is refused`() {
        assertFailsWith<UseCaseViolation> { service.upload(ByteArray(0), "image/png", "empty.png") }
    }

    @Test
    fun `a file over the limit is refused, and says how big it was`() {
        val huge = ByteArray((ImageRules.MAX_BYTES + 1).toInt())

        val failure = assertFailsWith<UseCaseViolation> { service.upload(huge, "image/jpeg", "huge.jpg") }

        assertTrue(failure.message!!.contains("MB"))
        assertTrue(store.stored.isEmpty())
    }

    /** A path from the client is a claim about someone else's filesystem, not a name. */
    @Test
    fun `a filename with a path keeps only its last part`() {
        assertEquals("photo.png", upload("/Users/someone/Desktop/photo.png").filename)
    }

    // ---- the gallery ------------------------------------------------------------------

    @Test
    fun `attaching puts an image in the product's gallery`() {
        val image = upload()

        service.attach(1, image.id!!)

        assertEquals(listOf(image.id), galleries.imagesOf(1).map { it.id })
    }

    @Test
    fun `attaching the same image twice changes nothing`() {
        val image = upload()
        service.attach(1, image.id!!)

        service.attach(1, image.id!!)

        assertEquals(1, galleries.countFor(1))
    }

    @Test
    fun `a product stops at the limit, and says what the limit is`() {
        repeat(ImageRules.MAX_PER_PRODUCT) { service.attach(1, upload("p$it.png").id!!) }
        val oneMore = upload("extra.png")

        val failure = assertFailsWith<UseCaseViolation> { service.attach(1, oneMore.id!!) }

        assertTrue(failure.message!!.contains("${ImageRules.MAX_PER_PRODUCT}"))
        assertEquals(ImageRules.MAX_PER_PRODUCT, galleries.countFor(1))
    }

    /** The whole point of a library: one photograph, several products. */
    @Test
    fun `one image can serve several products`() {
        products.save(productWith(id = 2, spuCode = "SPU-2"))
        val image = upload()

        service.attach(1, image.id!!)
        service.attach(2, image.id!!)

        assertEquals(2, images.usageOf(image.id!!).size)
    }

    @Test
    fun `reordering must name exactly the images the product has`() {
        val a = upload("a.png"); val b = upload("b.png")
        service.attach(1, a.id!!); service.attach(1, b.id!!)

        assertFailsWith<UseCaseViolation> { service.reorder(1, listOf(a.id!!)) }
        assertFailsWith<UseCaseViolation> { service.reorder(1, listOf(a.id!!, b.id!!, 999)) }

        service.reorder(1, listOf(b.id!!, a.id!!))
        assertEquals(listOf(b.id, a.id), galleries.imagesOf(1).map { it.id })
    }

    // ---- a SKU's main image -----------------------------------------------------------

    @Test
    fun `a SKU may point at one of its product's images`() {
        val image = upload()
        service.attach(1, image.id!!)

        service.setMainImage(1, variantId = 11, imageId = image.id)

        assertEquals(image.id, galleries.mainImageOf(11))
    }

    @Test
    fun `a SKU may not point at an image its product does not show`() {
        val loose = upload()

        assertFailsWith<UseCaseViolation> { service.setMainImage(1, 11, loose.id) }
    }

    @Test
    fun `a SKU from another product is refused`() {
        val image = upload()
        service.attach(1, image.id!!)

        assertFailsWith<UseCaseViolation> { service.setMainImage(1, variantId = 999, imageId = image.id) }
    }

    @Test
    fun `null clears it`() {
        val image = upload()
        service.attach(1, image.id!!)
        service.setMainImage(1, 11, image.id)

        service.setMainImage(1, 11, null)

        assertNull(galleries.mainImageOf(11))
    }

    /**
     * Otherwise the SKU goes on showing a photograph its own product no longer has — and
     * the gallery is the only place anyone would look to fix it.
     */
    @Test
    fun `detaching an image gives up being any SKU's main image`() {
        val image = upload()
        service.attach(1, image.id!!)
        service.setMainImage(1, 11, image.id)

        service.detach(1, image.id!!)

        assertNull(galleries.mainImageOf(11))
        assertEquals(0, galleries.countFor(1))
    }

    // ---- deleting ---------------------------------------------------------------------

    @Test
    fun `an image nothing uses is removed from the library and the bucket`() {
        val image = upload()

        service.delete(image.id!!)

        assertNull(images.findById(image.id!!))
        assertEquals(listOf("key-0"), store.deleted)
    }

    @Test
    fun `an image a product still shows is kept, and the refusal names the product`() {
        val image = upload()
        service.attach(1, image.id!!)

        val failure = assertFailsWith<UseCaseViolation> { service.delete(image.id!!) }

        assertTrue(failure.message!!.contains("SPU-1"))
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun `deleting something that is not there is a not-found`() {
        assertFailsWith<NoSuchElementException> { service.delete(999) }
    }

    // ---- the library screen -----------------------------------------------------------

    @Test
    fun `the library says what uses each image and whether it can go`() {
        val used = upload("used.png")
        val spare = upload("spare.png")
        service.attach(1, used.id!!)

        val library = service.library().associateBy { it.image.filename }

        assertEquals(listOf("SPU-1"), library.getValue("used.png").usedBy.map { it.spuCode })
        assertFalse(library.getValue("used.png").deletable)
        assertTrue(library.getValue("spare.png").usedBy.isEmpty())
        assertTrue(library.getValue("spare.png").deletable)
        assertEquals(spare.id, library.getValue("spare.png").image.id)
    }
}
