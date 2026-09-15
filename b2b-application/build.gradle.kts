plugins { kotlin("plugin.spring") }

// Use-case orchestration. Depends on the domain and, for @Service/@Transactional only,
// on spring-context and spring-tx — not on web or persistence. slf4j-api is a logging
// facade rather than a framework: a use case that changes data or calls out of the process
// has to leave a record, and the alternative was returning that record to the web layer
// purely so something with a logger could write it.
dependencies {
    api(project(":b2b-domain"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.slf4j:slf4j-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
