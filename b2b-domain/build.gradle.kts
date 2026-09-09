// Entities, aggregates, domain services and the port interfaces the domain owns.
// Depends on Domain Primitives only — no Spring, no JPA. `checkFrameworkFree` in the
// root build fails the build if that ever stops being true.
dependencies {
    api(project(":b2b-types"))
}
