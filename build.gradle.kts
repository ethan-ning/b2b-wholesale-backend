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
 * Java and Gradle each have a home Gradle insists on, and neither can read the version
 * catalog: the wrapper bootstraps before any build script runs, and the daemon JVM is
 * chosen before that. So the catalog holds the declared values and this task fails the
 * build if the real files drift from them.
 *
 *   gradle/libs.versions.toml            declared java + gradle (and kotlin, spring boot)
 *   gradle/gradle-daemon-jvm.properties  JVM the daemon runs on   -> ./gradlew updateDaemonJvm
 *   gradle/wrapper/gradle-wrapper.properties  Gradle itself       -> ./gradlew wrapper
 */
val checkVersionConsistency = tasks.register("checkVersionConsistency") {
    group = "verification"
    description = "Fails if the toolchain or wrapper drift from the versions declared in libs.versions.toml."

    val declaredJava = libs.versions.java.get()
    val declaredGradle = libs.versions.gradle.get()
    val daemonProps = layout.projectDirectory.file("gradle/gradle-daemon-jvm.properties").asFile
    val wrapperProps = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties").asFile

    doLast {
        val problems = mutableListOf<String>()

        val daemonVersion = daemonProps.takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("toolchainVersion=") }
            ?.substringAfter("=")
            ?.trim()
        if (daemonVersion == null) {
            problems += "gradle/gradle-daemon-jvm.properties is missing or has no toolchainVersion; run: ./gradlew updateDaemonJvm --jvm-version=$declaredJava"
        } else if (daemonVersion != declaredJava) {
            problems += "daemon JVM is $daemonVersion but libs.versions.toml declares java = \"$declaredJava\"; run: ./gradlew updateDaemonJvm --jvm-version=$declaredJava"
        }

        val wrapperVersion = wrapperProps.takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("distributionUrl=") }
            ?.let { Regex("gradle-([0-9.]+)-").find(it)?.groupValues?.get(1) }
        if (wrapperVersion == null) {
            problems += "could not read the Gradle version from gradle/wrapper/gradle-wrapper.properties"
        } else if (wrapperVersion != declaredGradle) {
            problems += "wrapper is Gradle $wrapperVersion but libs.versions.toml declares gradle = \"$declaredGradle\"; run: ./gradlew wrapper --gradle-version $declaredGradle"
        }

        check(problems.isEmpty()) {
            "Version drift:\n" + problems.joinToString("\n") { "  - $it" }
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
