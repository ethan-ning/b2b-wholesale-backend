plugins { alias(libs.plugins.kotlin.spring) }

// Controllers. Depends on the application layer only — never on infrastructure, so a
// controller cannot reach a repository or a DO.
dependencies {
    api(project(":b2b-application"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.kotlin)
    testImplementation(libs.spring.boot.starter.test)
}
