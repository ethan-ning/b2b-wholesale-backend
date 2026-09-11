package com.acme.b2b.web

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Path

/**
 * Serves what LocalImageStore wrote, so a development machine needs no bucket.
 *
 * Registers nothing when a bucket is configured: there Cloud Storage serves the files and
 * this path should not exist at all.
 *
 * The test is in code rather than in a @ConditionalOnProperty. The annotation reads as
 * though it says "only when no bucket is named", but an empty havingValue means "present
 * and not false", so with matchIfMissing it matched every case — and a deployment with a
 * bucket still published this route over a directory inside the container.
 */
@Configuration
class LocalImageRouting(
    @Value("\${app.images.bucket:}") private val bucket: String,
    @Value("\${app.images.local-dir:build/images}") private val directory: String,
) : WebMvcConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        if (bucket.isNotBlank()) return

        val absolute = Path.of(directory).toAbsolutePath()
        log.info("No image bucket configured — serving {} at /local-images", absolute)
        registry.addResourceHandler("/local-images/**")
            .addResourceLocations("file:$absolute/")
    }
}
