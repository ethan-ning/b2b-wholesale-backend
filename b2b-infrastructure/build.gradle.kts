plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

// Implements the domain's ports. This is the only module that knows JPA exists, and the
// only place a DO type is visible.
dependencies {
    api(project(":b2b-domain"))
    implementation(libs.spring.boot.starter.jpa)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
}
