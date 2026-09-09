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
