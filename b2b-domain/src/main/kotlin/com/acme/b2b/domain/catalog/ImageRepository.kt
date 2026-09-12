package com.acme.b2b.domain.catalog

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf

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
