plugins {
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

/*
 * Integration tests get their own source set and their own task.
 *
 * They need Docker, and unit tests must not: `./gradlew test` runs on a plane, and the
 * moment a Postgres-backed test shares that task nobody can tell which kind failed.
 * `check` depends on both, so CI still runs everything.
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

    // Integration tests only, so `test` cannot quietly grow a dependency on Docker.
    // The BOM and kotlin-test come from the root build for `test` alone, so name them here.
    "integrationTestImplementation"(platform("org.springframework.boot:spring-boot-dependencies:${property("spring-boot.version")}"))
    // test-junit5 explicitly: the plugin only infers the variant for the standard
    // `test` task, and a custom source set gets no kotlin.test.Test without it.
    "integrationTestImplementation"(kotlin("test-junit5"))
    "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "integrationTestRuntimeOnly"("org.postgresql:postgresql")
    "integrationTestRuntimeOnly"("org.flywaydb:flyway-database-postgresql")
}

/**
 * Where this machine's Docker actually listens.
 *
 * Testcontainers looks for /var/run/docker.sock and gives up if it is not there, which it
 * is not under Colima, Rancher or a rootless daemon. The active Docker context already
 * knows the answer, so ask it rather than making every developer export DOCKER_HOST.
 * Returns null when Docker is absent — the task then fails with Testcontainers' own
 * message, which says more than anything invented here.
 */
fun docker(vararg args: String): String? = runCatching {
    val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().trim()
        .takeIf { process.waitFor() == 0 && it.isNotBlank() }
}.getOrNull()

fun activeDockerHost(): String? = docker("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")

/**
 * The daemon's API version, as a system property.
 *
 * docker-java otherwise negotiates 1.32, which Colima and other current daemons refuse
 * outright ("client version 1.32 is too old"). The environment variable is not the knob —
 * docker-java reads the `api.version` property.
 */
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
                // Ryuk bind-mounts the socket by its in-container path, which stays the
                // conventional one however the host exposes it.
                environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
            }
        }
        serverApiVersion()?.let { systemProperty("api.version", it) }
    }
}

tasks.named("check") { dependsOn(integrationTest) }
