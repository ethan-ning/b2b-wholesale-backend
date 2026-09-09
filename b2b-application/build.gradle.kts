plugins { kotlin("plugin.spring") }

// Use-case orchestration. Depends on the domain and, for @Service/@Transactional only,
// on spring-context and spring-tx — not on web or persistence.
dependencies {
    api(project(":b2b-domain"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
