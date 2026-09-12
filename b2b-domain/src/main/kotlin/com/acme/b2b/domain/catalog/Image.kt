package com.acme.b2b.domain.catalog

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
 * An image as a product shows it: enough to render the gallery, without the library's
 * bookkeeping. The order is the gallery's order, and the first is the product's main one.
 */
data class ProductImageRef(val id: Long, val url: String, val altText: String?)
