plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "com.acme.b2b"
    version = "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(rootProject.libs.versions.java.get().toInt())
        compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
    }

    dependencies {
        // Gradle-native dependency management: the Boot BOM is a platform, so module
        // builds name libraries without versions and cannot drift apart.
        "implementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(kotlin("test"))
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

/**
 * The Gradle version cannot live in the version catalog — the wrapper bootstraps before
 * any build script runs — so the catalog holds the declared value and this task fails if
 * the wrapper drifts from it.
 *
 * Java needs no such check: `java` in the catalog feeds `jvmToolchain()` directly, which
 * is what determines the bytecode.
 */
val checkVersionConsistency = tasks.register("checkVersionConsistency") {
    group = "verification"
    description = "Fails if the Gradle wrapper drifts from the version declared in libs.versions.toml."

    val declaredGradle = libs.versions.gradle.get()
    val wrapperProps = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties").asFile

    doLast {
        val wrapperVersion = wrapperProps.takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("distributionUrl=") }
            ?.let { Regex("gradle-([0-9.]+)-").find(it)?.groupValues?.get(1) }

        check(wrapperVersion != null) {
            "Could not read the Gradle version from gradle/wrapper/gradle-wrapper.properties"
        }
        check(wrapperVersion == declaredGradle) {
            "Wrapper is Gradle $wrapperVersion but libs.versions.toml declares gradle = \"$declaredGradle\"; " +
                "run: ./gradlew wrapper --gradle-version $declaredGradle"
        }
    }
}

/**
 * Guards the rule the module graph cannot express on its own: the two innermost layers
 * must stay free of framework types, so business rules unit-test with no container and
 * are not pinned to Spring or JPA.
 */
val frameworkFreeModules = setOf("b2b-types", "b2b-domain")
val forbiddenGroups = listOf("org.springframework", "jakarta.persistence", "org.hibernate")

subprojects {
    if (name in frameworkFreeModules) {
        val guard = tasks.register("checkFrameworkFree") {
            group = "verification"
            description = "Fails if this module gained a framework dependency."
            val moduleName = name
            val artifacts = configurations.named("compileClasspath").map { config ->
                config.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.group }
            }
            doLast {
                val offenders = artifacts.get()
                    .filter { group -> forbiddenGroups.any { group.startsWith(it) } }
                    .distinct()
                check(offenders.isEmpty()) {
                    "$moduleName must stay framework-free but depends on: ${offenders.joinToString()}"
                }
            }
        }
        tasks.named("check") { dependsOn(guard) }
    }
}
