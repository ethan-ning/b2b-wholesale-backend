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
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}

// A developer running bootRun means local; production sets SPRING_PROFILES_ACTIVE itself.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", "local")
}
