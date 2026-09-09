package com.acme.b2b.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Fails startup with a readable message when a required setting is missing.
 *
 * Without this the failure still happens, but as `'url' must start with "jdbc"` from
 * deep inside Hikari's bean creation, which never mentions that DB_URL was unset.
 * Running as an EnvironmentPostProcessor means the report comes before any bean is
 * built, so it is the first thing in the log rather than the last.
 *
 * Production is the baseline configuration, so the check applies by default. A profile
 * that ships working defaults of its own opts out.
 */
class RequiredSettingsCheck : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.activeProfiles.any { it in PROFILES_WITH_DEFAULTS }) return

        val missing = REQUIRED.filterNot { (_, variable) ->
            environment.getProperty(variable)?.isNotBlank() == true
        }
        if (missing.isEmpty()) return

        throw IllegalStateException(
            buildString {
                appendLine("Cannot start: required settings are missing.")
                missing.forEach { (property, variable) ->
                    appendLine("  - $variable  (binds to $property)")
                }
                append(
                    "Set them in the environment, or run locally with " +
                        "SPRING_PROFILES_ACTIVE=local. The default configuration is production " +
                        "and has no fallbacks by design."
                )
            }
        )
    }

    private companion object {
        /** Profiles that supply their own defaults, so nothing is required of the environment. */
        val PROFILES_WITH_DEFAULTS = setOf("local", "test")

        /** property it binds to -> environment variable that supplies it */
        val REQUIRED = mapOf(
            "spring.datasource.url" to "DB_URL",
            "spring.datasource.username" to "DB_USER",
            "spring.datasource.password" to "DB_PASSWORD",
            "security.jwt.secret" to "JWT_SECRET",
            "sellfox.client-id" to "SELLFOX_APP_ID",
            "sellfox.client-secret" to "SELLFOX_APP_SECRET",
        )
    }
}
