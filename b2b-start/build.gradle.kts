plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

// The deployable. Wires the layers together and owns framework configuration; it is the
// only module that depends on both web and infrastructure.
dependencies {
    implementation(project(":b2b-web"))
    implementation(project(":b2b-infrastructure"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.h2)
}
