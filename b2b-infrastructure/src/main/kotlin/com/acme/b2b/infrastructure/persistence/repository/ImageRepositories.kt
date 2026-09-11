package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.catalog.Image
import com.acme.b2b.domain.catalog.ImageRepository
import com.acme.b2b.domain.catalog.ImageUsage
import com.acme.b2b.domain.catalog.ProductImageRepository
import com.acme.b2b.domain.catalog.UsedBy
import com.acme.b2b.infrastructure.persistence.entity.ImageDO
import com.acme.b2b.infrastructure.persistence.entity.ProductImageDO
import com.acme.b2b.infrastructure.persistence.jpa.ImageJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductImageJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class ImageRepositoryImpl(
    private val jpa: ImageJpaRepository,
    private val links: ProductImageJpaRepository,
) : ImageRepository {

    override fun findById(id: Long): Image? = jpa.findById(id).orElse(null)?.let(::toDomain)

    override fun findByObjectKey(objectKey: String): Image? = jpa.findByObjectKey(objectKey)?.let(::toDomain)

    override fun save(image: Image): Image {
        val row = image.id?.let { jpa.findById(it).orElse(null) } ?: ImageDO()
        row.url = image.url
        row.objectKey = image.objectKey
        row.filename = image.filename
        row.contentType = image.contentType
        row.bytes = image.bytes
        row.width = image.width
        row.height = image.height
        row.altText = image.altText
        if (row.uploadedAt == null) row.uploadedAt = Instant.now()
        return toDomain(jpa.save(row))
    }

    override fun deleteById(id: Long) = jpa.deleteById(id)

    /**
     * Every image with the products showing it, in one pass rather than a usage query per
     * row — the library is a single screen and a hundred images would be a hundred trips.
     */
    override fun findAllWithUsage(): List<ImageUsage> {
        val usageByImage = links.findAll()
            .mapNotNull { link -> link.image?.id?.let { it to link } }
            .groupBy({ it.first }, { it.second })

        return jpa.findAll()
            .sortedByDescending { it.uploadedAt ?: Instant.EPOCH }
            .map { row -> ImageUsage(toDomain(row), usageByImage[row.id].orEmpty().map(::toUsedBy)) }
    }

    override fun usageOf(imageId: Long): List<UsedBy> = links.findByImageId(imageId).map(::toUsedBy)

    private fun toUsedBy(link: ProductImageDO) = UsedBy(
        productId = link.product?.id ?: 0,
        spuCode = link.product?.spuCode ?: "",
        name = link.product?.name ?: "",
    )

    private fun toDomain(row: ImageDO) = Image(
        id = row.id,
        url = row.url,
        objectKey = row.objectKey,
        filename = row.filename,
        contentType = row.contentType,
        bytes = row.bytes,
        width = row.width,
        height = row.height,
        altText = row.altText,
        uploadedAt = row.uploadedAt,
    )
}

@Repository
class ProductImageRepositoryImpl(
    private val links: ProductImageJpaRepository,
    private val imagesJpa: ImageJpaRepository,
    private val products: ProductJpaRepository,
    private val variants: ProductVariantJpaRepository,
) : ProductImageRepository {

    override fun imagesOf(productId: Long): List<Image> =
        links.findByProductIdOrderBySortOrderAsc(productId).mapNotNull { link ->
            link.image?.let {
                Image(it.id, it.url, it.objectKey, it.filename, it.contentType, it.bytes, it.width, it.height, it.altText, it.uploadedAt)
            }
        }

    override fun countFor(productId: Long): Int = links.countByProductId(productId).toInt()

    override fun isAttached(productId: Long, imageId: Long): Boolean =
        links.findByProductIdAndImageId(productId, imageId) != null

    /*
     * Attach, detach and reorder all go through ProductDO.images rather than the link
     * table directly.
     *
     * That collection is mapped cascade-all with orphan removal and fetched eagerly, so
     * within one transaction it is the authority: a row deleted straight from the link
     * table came back at flush, because the product loaded a moment earlier still had it.
     * The delete reported success and the image stayed on the product.
     */
    @Transactional
    override fun attach(productId: Long, imageId: Long) {
        val product = products.findById(productId).orElseThrow()
        // Appended, so a new image arrives at the end rather than displacing the main one.
        val next = product.images.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        product.images.add(
            ProductImageDO(
                product = product,
                image = imagesJpa.findById(imageId).orElseThrow(),
                sortOrder = next,
            )
        )
        products.save(product)
    }

    @Transactional
    override fun detach(productId: Long, imageId: Long) {
        val product = products.findById(productId).orElseThrow()
        product.images.removeIf { it.image?.id == imageId }
        products.save(product)
    }

    @Transactional
    override fun reorder(productId: Long, imageIdsInOrder: List<Long>) {
        val product = products.findById(productId).orElseThrow()
        product.images.forEach { link ->
            val position = imageIdsInOrder.indexOf(link.image?.id)
            if (position >= 0) link.sortOrder = position
        }
        products.save(product)
    }

    @Transactional
    override fun setMainImage(variantId: Long, imageId: Long?) {
        val variant = variants.findById(variantId).orElseThrow()
        variant.mainImageId = imageId
        variants.save(variant)
    }

    @Transactional
    override fun clearMainImage(productId: Long, imageId: Long) {
        val affected = variants.findByProductId(productId).filter { it.mainImageId == imageId }
        affected.forEach { it.mainImageId = null }
        variants.saveAll(affected)
    }
}
