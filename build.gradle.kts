plugins {
    kotlin("jvm") apply false
    kotlin("plugin.spring") apply false
    kotlin("plugin.jpa") apply false
    id("org.springframework.boot") apply false
}

// Versions come from gradle.properties. Dotted names cannot use the `by project`
// delegate, which requires the property name to match the variable name.
val javaVersion = providers.gradleProperty("java.version").get()
val springBootVersion = providers.gradleProperty("spring-boot.version").get()

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

    // Coverage, so "improve the tests" can be aimed rather than guessed at.
    apply(plugin = "jacoco")
    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports { xml.required.set(true); html.required.set(true) }
    }
}

/**
 * The dependency rule, enforced. Each module may depend only on the modules listed for
 * it — notably infrastructure and web may not depend on each other, and neither may an
 * adapter depend on the application layer.
 *
 * The graph is normally self-enforcing, since a module simply cannot see what it does not
 * declare. This catches the other direction: someone adding a declaration that should not
 * exist, which compiles perfectly well and quietly inverts a layer.
 */
val allowedDependencies = mapOf(
    "b2b-types" to emptySet(),
    "b2b-domain" to setOf("b2b-types"),
    "b2b-application" to setOf("b2b-types", "b2b-domain"),
    "b2b-infrastructure" to setOf("b2b-types", "b2b-domain"),
    "b2b-web" to setOf("b2b-types", "b2b-domain", "b2b-application"),
    "b2b-start" to setOf("b2b-types", "b2b-domain", "b2b-application", "b2b-infrastructure", "b2b-web"),
)

subprojects {
    val allowed = allowedDependencies[name] ?: return@subprojects
    val guard = tasks.register("checkLayering") {
        group = "verification"
        description = "Fails if this module declares a project dependency the layering forbids."
        val moduleName = name
        val declared = provider {
            listOf("api", "implementation", "runtimeOnly", "compileOnly")
                .mapNotNull { configurations.findByName(it) }
                .flatMap { it.dependencies }
                .filterIsInstance<ProjectDependency>()
                .map { it.name }
                .distinct()
        }
        doLast {
            val forbidden = declared.get().filterNot { it in allowed }
            check(forbidden.isEmpty()) {
                "$moduleName may depend on ${allowed.sorted()} but also declares: ${forbidden.sorted()}"
            }
        }
    }
    tasks.named("check") { dependsOn(guard) }
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
