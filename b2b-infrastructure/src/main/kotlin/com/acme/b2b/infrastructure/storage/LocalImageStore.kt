package com.acme.b2b.infrastructure.storage

import com.acme.b2b.domain.catalog.ImageStore
import com.acme.b2b.domain.catalog.StoredObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists

/**
 * Where uploads go when no bucket is configured — a directory on disk, served back by the
 * app itself.
 *
 * Its purpose is that running locally needs no cloud credentials and writes nothing to the
 * bucket a deployment shares. Chosen exactly when app.images.bucket is unset.
 */
class LocalImageStore(
    private val directory: String,
) : ImageStore {

    private val root: Path by lazy { Path.of(directory).also { Files.createDirectories(it) } }

    override fun put(content: ByteArray, contentType: String, filename: String): StoredObject {
        val extension = filename.substringAfterLast('.', "jpg").lowercase().take(5)
        val key = "${UUID.randomUUID()}.$extension"
        Files.write(root.resolve(key), content)
        val size = ImageDimensions.of(content)
        return StoredObject(
            objectKey = key,
            url = "/local-images/$key",
            width = size?.first,
            height = size?.second,
        )
    }

    override fun delete(objectKey: String) {
        runCatching { root.resolve(objectKey).deleteIfExists() }
    }
}
