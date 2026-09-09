plugins {
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

// Implements the domain's ports. This is the only module that knows JPA exists, and the
// only place a DO type is visible.
dependencies {
    api(project(":b2b-domain"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}
