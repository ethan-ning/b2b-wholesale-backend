package com.acme.b2b.infrastructure.storage

import com.acme.b2b.domain.catalog.ImageStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Which store the uploads go to: the bucket when one is named, a directory on disk when
 * not.
 *
 * Decided here, in code, rather than by a condition on each class. @ConditionalOnMissingBean
 * is only dependable on auto-configuration, and on a scanned @Component it silently left the
 * application with no ImageStore at all — a startup failure rather than the local fallback
 * it was meant to be.
 */
@Configuration
class ImageStoreConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun imageStore(
        @Value("\${app.images.bucket:}") bucket: String,
        @Value("\${app.images.local-dir:build/images}") localDirectory: String,
    ): ImageStore {
        if (bucket.isNotBlank()) {
            log.info("Product images go to gs://{}", bucket)
            return GcsImageStore(bucket)
        }
        log.info("app.images.bucket is unset — product images go to {} and are served from /local-images", localDirectory)
        return LocalImageStore(localDirectory)
    }
}
