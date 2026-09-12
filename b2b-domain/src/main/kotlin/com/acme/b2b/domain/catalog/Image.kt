package com.acme.b2b.domain.catalog

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import java.time.Instant

/**
 * A picture, owned by nobody in particular.
 *
 * Separate from the products that show it: the same photograph is often right for several
 * SKUs, and a model where each product owns its own copy cannot say whether anything still
 * uses a file before deleting it.
 */
data class Image(
    val id: Long?,
    /** What a browser loads. */
    val url: String,
    /** Where it sits in the bucket, or null for a link to somewhere we do not control. */
    val objectKey: String?,
    val filename: String,
    val contentType: String?,
    val bytes: Long?,
    val width: Int?,
    val height: Int?,
    val altText: String?,
    val uploadedAt: Instant?,
) {
    /** Ours to remove from storage. A link to someone else's server is not. */
    val isStored: Boolean get() = objectKey != null
}

/** An image with what is keeping it alive, for the library screen. */
data class ImageUsage(
    val image: Image,
    val products: List<UsedBy>,
) {
    val usedByCount: Int get() = products.size

    /** Only an image nothing shows can be removed — the rule the library screen enforces. */
    val deletable: Boolean get() = products.isEmpty()
}

data class UsedBy(val productId: Long, val spuCode: String, val name: String)

object ImageRules {
    /**
     * Per product, not per SKU. Enough for a gallery that a dealer will actually page
     * through, and few enough that the set stays curated rather than a dump of everything
     * the vendor sent.
     */
    const val MAX_PER_PRODUCT = 9

    /** Five megabytes: large enough for product photography, small enough that a mis-drag costs nothing. */
    const val MAX_BYTES = 5L * 1024 * 1024

    val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
}

/**
 * What the library screen is asking for.
 *
 * [term] matches an image by its filename or by any product showing it, because "which
 * images belong to this SPU" is the other question the screen exists to answer.
 */
data class ImageSearch(val term: String? = null, val unusedOnly: Boolean = false)

interface ImageRepository {
    fun findById(id: Long): Image?
    fun findByObjectKey(objectKey: String): Image?
    fun save(image: Image): Image
    fun deleteById(id: Long)

    /**
     * One screenful of the library, with what is keeping each image alive.
     *
     * Paged in the query rather than in memory. A catalogue's worth of pictures is a few
     * hundred kilobytes of JSON and every row carries its usage, so fetching the lot to
     * show twenty of them makes the first paint wait on all of it.
     */
    fun findPageWithUsage(search: ImageSearch, page: Page): PageOf<ImageUsage>

    /** How many images nothing shows — the ones that can actually be deleted. */
    fun countUnused(): Long

    fun usageOf(imageId: Long): List<UsedBy>
}

/**
 * A product's gallery, and which of its images stands for each SKU.
 *
 * That a SKU's main image belongs to that SKU's own product is not something a foreign key
 * can reach far enough to say, so it is checked where the rule is written down.
 */
interface ProductImageRepository {
    fun imagesOf(productId: Long): List<Image>
    fun countFor(productId: Long): Int
    fun isAttached(productId: Long, imageId: Long): Boolean
    fun attach(productId: Long, imageId: Long)
    fun detach(productId: Long, imageId: Long)
    /** Rewrites sort order wholesale; position 0 is the product's main image. */
    fun reorder(productId: Long, imageIdsInOrder: List<Long>)

    fun setMainImage(variantId: Long, imageId: Long?)
    /** Clears the pointer wherever it is used, for an image leaving a product. */
    fun clearMainImage(productId: Long, imageId: Long)
}

/** Port for the bucket. The application layer never learns which cloud it is. */
interface ImageStore {
    /**
     * Stores the bytes and reports what they turned out to be.
     *
     * Pixel size comes back from here rather than being read by the caller: decoding an
     * image depends on which readers the runtime happens to provide, and that is the kind
     * of thing an adapter absorbs.
     */
    fun put(content: ByteArray, contentType: String, filename: String): StoredObject
    fun delete(objectKey: String)
}

/** Where the file went, and its pixel size where the format could be read. */
data class StoredObject(
    val objectKey: String,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * An image as a product shows it: enough to render the gallery, without the library's
 * bookkeeping. The order is the gallery's order, and the first is the product's main one.
 */
data class ProductImageRef(val id: Long, val url: String, val altText: String?)
