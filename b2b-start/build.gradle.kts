plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

// The deployable. Wires the layers together and owns framework configuration; it is the
// only module that depends on both web and infrastructure.
dependencies {
    implementation(project(":b2b-web"))
    implementation(project(":b2b-infrastructure"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Health probes for the load balancer; the management config in application.yml
    // is inert without this, and /actuator/health 401s as an unmatched route.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    // Cloud SQL reaches the database over a unix socket that Cloud Run mounts, and the
    // Postgres driver cannot speak to one on its own. Runtime only, and inert unless the
    // JDBC URL names the socket factory — local development is unaffected.
    runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.25.2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}

// A developer running bootRun means local; production sets SPRING_PROFILES_ACTIVE itself.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", "local")
}
