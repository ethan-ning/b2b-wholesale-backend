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
        jvmToolchain(25)
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
