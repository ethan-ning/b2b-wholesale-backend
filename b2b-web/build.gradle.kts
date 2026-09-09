plugins { kotlin("plugin.spring") }

// Controllers. Depends on the application layer only — never on infrastructure, so a
// controller cannot reach a repository or a DO.
dependencies {
    api(project(":b2b-application"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
