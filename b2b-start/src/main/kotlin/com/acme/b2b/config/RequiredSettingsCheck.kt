package com.acme.b2b.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Fails startup with a readable message when a required setting is missing.
 *
 * Without this the failure still happens, but as `'url' must start with "jdbc"` from
 * deep inside Hikari's bean creation — which does not tell whoever is deploying that
 * DB_URL was never set. Running as an EnvironmentPostProcessor means it reports before
 * any bean is built, so the message is the first thing in the log rather than the last.
 *
 * Only the prod profile is checked: local supplies working defaults on purpose.
 */
class RequiredSettingsCheck : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (PROD_PROFILE !in environment.activeProfiles) return

        val missing = REQUIRED.filterNot { (_, variable) ->
            environment.getProperty(variable)?.isNotBlank() == true
        }
        if (missing.isEmpty()) return

        throw IllegalStateException(
            buildString {
                appendLine("Cannot start with profile '$PROD_PROFILE': required settings are missing.")
                missing.forEach { (property, variable) ->
                    appendLine("  - $variable  (binds to $property)")
                }
                append("Set them in the environment; the prod profile has no fallbacks by design.")
            }
        )
    }

    private companion object {
        const val PROD_PROFILE = "prod"

        /** property it binds to -> environment variable that supplies it */
        val REQUIRED = mapOf(
            "spring.datasource.url" to "DB_URL",
            "spring.datasource.username" to "DB_USER",
            "spring.datasource.password" to "DB_PASSWORD",
            "security.jwt.secret" to "JWT_SECRET",
        )
    }
}
