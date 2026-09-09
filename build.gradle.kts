plugins {
    kotlin("jvm") apply false
    kotlin("plugin.spring") apply false
    kotlin("plugin.jpa") apply false
    id("org.springframework.boot") apply false
}

// Versions come from gradle.properties.
val javaVersion: String by project
val springBootVersion: String by project

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(javaVersion.toInt())
        compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
    }

    dependencies {
        // Gradle-native dependency management: the Boot BOM is applied as a platform, so
        // module builds name libraries without versions and cannot drift apart.
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
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
