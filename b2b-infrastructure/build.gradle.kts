plugins {
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

/*
 * Integration tests get their own source set and task: they need Docker, and unit tests
 * must not. `check` depends on both.
 *
 * Declared before `dependencies` because that block names the configurations this creates.
 */
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations["implementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

// Implements the domain's ports. This is the only module that knows JPA exists, and the
// only place a DO type is visible.
dependencies {
    api(project(":b2b-domain"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // The schema lives with the DOs it creates tables for: src/main/resources/db/migration.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // BCrypt for the PasswordHasher port, and Nimbus (via the resource server) for JWTs.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")

    // The root build gives the BOM and kotlin-test to `test` alone, so name them again.
    "integrationTestImplementation"(platform("org.springframework.boot:spring-boot-dependencies:${property("spring-boot.version")}"))
    // test-junit5, not test: the plugin infers the variant only for the standard task.
    "integrationTestImplementation"(kotlin("test-junit5"))
    "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "integrationTestRuntimeOnly"("org.postgresql:postgresql")
    "integrationTestRuntimeOnly"("org.flywaydb:flyway-database-postgresql")
}

/**
 * Asks Docker about itself, or null when it is not there.
 *
 * Testcontainers looks for /var/run/docker.sock and gives up when it is elsewhere, as it
 * is under Colima, Rancher and rootless daemons. The active context already knows, so ask
 * it rather than making every developer export DOCKER_HOST.
 */
fun docker(vararg args: String): String? = runCatching {
    val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().trim()
        .takeIf { process.waitFor() == 0 && it.isNotBlank() }
}.getOrNull()

fun activeDockerHost(): String? = docker("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")

/** docker-java otherwise negotiates 1.32, which current daemons refuse as too old. */
fun serverApiVersion(): String? = docker("version", "--format", "{{.Server.APIVersion}}")

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Repository and migration tests against a real Postgres (needs Docker)."
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))

    doFirst {
        if (System.getenv("DOCKER_HOST") == null) {
            activeDockerHost()?.let { host ->
                environment("DOCKER_HOST", host)
                // Ryuk mounts the socket by its in-container path, wherever the host keeps it.
                environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
            }
        }
        serverApiVersion()?.let { systemProperty("api.version", it) }
    }
}

tasks.named("check") { dependsOn(integrationTest) }

/*
 * Coverage here comes from integrationTest: the persistence layer has no unit tests and is
 * not meant to. Reporting on `test` alone left the largest module out of the total at zero.
 */
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(integrationTest)
    executionData(integrationTest.get())
}
