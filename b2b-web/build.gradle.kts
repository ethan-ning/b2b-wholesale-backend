plugins { kotlin("plugin.spring") }

// Controllers. Depends on the application layer only — never on infrastructure, so a
// controller cannot reach a repository or a DO.
dependencies {
    api(project(":b2b-application"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    // The filter chain protecting this module's endpoints. The token's algorithm and key
    // live with the issuer in b2b-infrastructure; only the JwtDecoder bean is consumed.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
