plugins { alias(libs.plugins.kotlin.spring) }

// Use-case orchestration. Depends on the domain and, for @Service/@Transactional only,
// on spring-context and spring-tx — not on web or persistence.
dependencies {
    api(project(":b2b-domain"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
    testImplementation(libs.spring.boot.starter.test)
}
