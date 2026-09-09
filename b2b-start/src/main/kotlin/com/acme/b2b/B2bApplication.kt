package com.acme.b2b

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Component scanning starts at com.acme.b2b so it reaches every layer's beans, but the
 * layering is enforced by the module graph rather than by scan configuration.
 */
@SpringBootApplication(scanBasePackages = ["com.acme.b2b"])
class B2bApplication

fun main(args: Array<String>) {
    runApplication<B2bApplication>(*args)
}
