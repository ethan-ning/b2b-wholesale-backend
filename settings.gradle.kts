// Plugin versions come from gradle.properties. They have to be resolved here: the
// plugins {} block in a build script cannot read project properties, but `by settings`
// can, so each plugin is versioned once in this block and applied without a version
// everywhere else.
pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlin.version").get()
    val springBootVersion = providers.gradleProperty("spring-boot.version").get()

    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.spring") version kotlinVersion
        kotlin("plugin.jpa") version kotlinVersion
        id("org.springframework.boot") version springBootVersion
    }
}

rootProject.name = "b2b-wholesale"

// Module order mirrors the dependency direction: each one may only depend on
// those above it. See ARCHITECTURE.md.
include(
    "b2b-types",           // Domain Primitives — zero dependencies
    "b2b-domain",          // Entities, aggregates, domain services, port interfaces
    "b2b-application",     // Use-case orchestration, DTOs, assemblers
    "b2b-infrastructure",  // Persistence, ACL adapters — implements domain ports
    "b2b-web",             // Controllers
    "b2b-start",           // Spring Boot entry point and configuration
)
