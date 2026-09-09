// Needed only by `updateDaemonJvm`, which asks it for the per-platform JDK download URLs
// it bakes into gradle/gradle-daemon-jvm.properties. Day-to-day builds do not use it:
// those URLs are already in that file, and once the daemon is pinned to Java 21 its own
// JVM satisfies the compile toolchain.
//
// Kept because checkVersionConsistency tells you to run `updateDaemonJvm` when the java
// version in the catalog changes, and that command fails without this plugin.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
