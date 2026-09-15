package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.ImageDTO
import com.acme.b2b.application.admin.dto.ImageLibraryDTO
import com.acme.b2b.application.admin.dto.ImageUsageDTO
import com.acme.b2b.application.admin.dto.UsedByDTO
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.Image
import com.acme.b2b.domain.catalog.ImageRepository
import com.acme.b2b.domain.catalog.ImageSearch
import com.acme.b2b.domain.catalog.ImageRules
import com.acme.b2b.domain.catalog.ImageStore
import com.acme.b2b.domain.catalog.ImageUsage
import com.acme.b2b.domain.catalog.ProductImageRepository
import com.acme.b2b.domain.catalog.ProductRepository
import com.acme.b2b.domain.common.Page
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The image library, and each product's gallery.
 *
 * Uploading and attaching are separate steps on purpose: an image belongs to the library
 * first and to a product second, which is what lets one photograph serve several SKUs.
 */
@Service
@Transactional(readOnly = true)
class ImageAdminService(
    private val images: ImageRepository,
    private val galleries: ProductImageRepository,
    private val products: ProductRepository,
    private val store: ImageStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun library(search: String?, unusedOnly: Boolean, page: Page): ImageLibraryDTO {
        val found = images.findPageWithUsage(ImageSearch(search, unusedOnly), page)
        return ImageLibraryDTO(
            content = found.content.map(::toDTO),
            totalElements = found.totalElements,
            totalPages = found.totalPages,
            page = page.number,
            size = page.size,
            unusedCount = images.countUnused(),
        )
    }

    @Transactional
    fun upload(content: ByteArray, contentType: String?, filename: String?): ImageDTO {
        val type = contentType?.lowercase()?.substringBefore(';')?.trim()
        if (type == null || type !in ImageRules.ALLOWED_CONTENT_TYPES) {
            throw UseCaseViolation("Images must be JPEG, PNG or WebP — got ${contentType ?: "nothing"}")
        }
        if (content.isEmpty()) throw UseCaseViolation("That file is empty")
        if (content.size > ImageRules.MAX_BYTES) {
            throw UseCaseViolation(
                "Images must be under ${ImageRules.MAX_BYTES / 1024 / 1024} MB — that one is " +
                    "${"%.1f".format(content.size / 1024.0 / 1024.0)} MB"
            )
        }

        val safeName = filename?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('/') ?: "image"
        val stored = store.put(content, type, safeName)

        return toDTO(
            images.save(
                Image(
                    id = null,
                    url = stored.url,
                    objectKey = stored.objectKey,
                    filename = safeName,
                    contentType = type,
                    bytes = content.size.toLong(),
                    width = stored.width,
                    height = stored.height,
                    altText = null,
                    uploadedAt = null,
                )
            )
        )
    }

    /**
     * Removes the row and the file. Refused while anything shows it — the caller detaches
     * first, deliberately, rather than discovering later that a gallery has a hole in it.
     */
    @Transactional
    fun delete(imageId: Long) {
        val image = images.findById(imageId) ?: throw NoSuchElementException("No such image")
        val usedBy = images.usageOf(imageId)
        if (usedBy.isNotEmpty()) {
            throw UseCaseViolation(
                "Still used by ${usedBy.size} product${if (usedBy.size == 1) "" else "s"}: " +
                    usedBy.take(3).joinToString { it.spuCode } + if (usedBy.size > 3) "…" else ""
            )
        }

        images.deleteById(imageId)
        // After the row, so a bucket failure does not leave a row pointing at nothing.
        image.objectKey?.let(store::delete)
        log.warn("Image {} ({}) deleted", imageId, image.filename)
    }

    @Transactional
    fun attach(productId: Long, imageId: Long) {
        requireProduct(productId)
        images.findById(imageId) ?: throw NoSuchElementException("No such image")

        if (galleries.isAttached(productId, imageId)) return
        if (galleries.countFor(productId) >= ImageRules.MAX_PER_PRODUCT) {
            throw UseCaseViolation("A product shows at most ${ImageRules.MAX_PER_PRODUCT} images")
        }
        galleries.attach(productId, imageId)
    }

    /**
     * Leaving the gallery also gives up being any SKU's main image. Without that a SKU
     * would go on showing a photograph its own product no longer has.
     */
    @Transactional
    fun detach(productId: Long, imageId: Long) {
        requireProduct(productId)
        galleries.clearMainImage(productId, imageId)
        galleries.detach(productId, imageId)
    }

    @Transactional
    fun reorder(productId: Long, imageIdsInOrder: List<Long>) {
        requireProduct(productId)
        val current = galleries.imagesOf(productId).mapNotNull { it.id }.toSet()
        if (imageIdsInOrder.toSet() != current) {
            throw UseCaseViolation("The new order must list exactly the images this product has")
        }
        galleries.reorder(productId, imageIdsInOrder)
    }

    /** Null clears it. A SKU may only point at an image its own product shows. */
    @Transactional
    fun setMainImage(productId: Long, variantId: Long, imageId: Long?) {
        val product = requireProduct(productId)
        if (product.variants.none { it.id == variantId }) {
            throw UseCaseViolation("That SKU does not belong to this product")
        }
        if (imageId != null && !galleries.isAttached(productId, imageId)) {
            throw UseCaseViolation("A SKU's main image must be one of its product's images")
        }
        galleries.setMainImage(variantId, imageId)
    }

    private fun requireProduct(productId: Long) =
        products.findById(productId) ?: throw NoSuchElementException("No such product")

    private fun toDTO(usage: ImageUsage) = ImageUsageDTO(
        image = toDTO(usage.image),
        usedBy = usage.products.map { UsedByDTO(it.productId, it.spuCode, it.name) },
        deletable = usage.deletable,
    )

    private fun toDTO(image: Image) = ImageDTO(
        id = image.id,
        url = image.url,
        filename = image.filename,
        contentType = image.contentType,
        bytes = image.bytes,
        width = image.width,
        height = image.height,
        altText = image.altText,
        stored = image.isStored,
    )
}
