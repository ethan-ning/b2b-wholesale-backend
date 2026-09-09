// Lets Gradle download the Java 25 toolchain on a machine that does not have it, so the
// project's JDK is a property of the build rather than of whoever is building it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
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
