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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}
