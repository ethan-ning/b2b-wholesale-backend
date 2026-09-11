package com.acme.b2b.infrastructure.storage

import com.acme.b2b.domain.catalog.ImageStore
import com.acme.b2b.domain.catalog.StoredObject
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Product images in a Cloud Storage bucket.
 *
 * Objects are named by UUID rather than by the file they arrived as: two vendors both
 * sending "1.jpg" must not overwrite each other, and a key nobody can guess is what keeps
 * a world-readable bucket from being a directory anyone can walk.
 *
 * The bucket is public, so the URL is derived rather than signed. A product photograph is
 * not confidential — the prices beside it are, and those never leave the API.
 */
class GcsImageStore(
    private val bucket: String,
) : ImageStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val storage: Storage by lazy { StorageOptions.getDefaultInstance().service }

    override fun put(content: ByteArray, contentType: String, filename: String): StoredObject {
        val key = "${UUID.randomUUID()}${extensionOf(filename, contentType)}"
        val blob = BlobInfo.newBuilder(BlobId.of(bucket, key))
            .setContentType(contentType)
            // A year: the key changes whenever the bytes do, so a cached copy can never be
            // the wrong picture.
            .setCacheControl("public, max-age=31536000, immutable")
            .build()

        storage.create(blob, content)
        val size = ImageDimensions.of(content)
        return StoredObject(
            objectKey = key,
            url = "https://storage.googleapis.com/$bucket/$key",
            width = size?.first,
            height = size?.second,
        )
    }

    /**
     * A missing object is not an error worth failing on: the row is already gone, and the
     * only thing left to do about a file that is not there is nothing.
     */
    override fun delete(objectKey: String) {
        runCatching { storage.delete(BlobId.of(bucket, objectKey)) }
            .onFailure { log.warn("Could not delete {} from {}: {}", objectKey, bucket, it.message) }
    }

    private fun extensionOf(filename: String, contentType: String): String {
        val fromName = filename.substringAfterLast('.', "").lowercase()
        if (fromName.length in 1..5 && fromName.all { it.isLetterOrDigit() }) return ".$fromName"
        return when (contentType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
    }
}
