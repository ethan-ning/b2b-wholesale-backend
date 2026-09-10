package com.acme.b2b.infrastructure

import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.SpringBootConfiguration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * A real Postgres, the real migration, the real mappings.
 *
 * For the things a fake cannot be wrong about in the same way: whether a cascade fires,
 * whether a flush ordering behaves as assumed, whether the schema matches what Hibernate
 * validates against. Everything else is a unit test.
 *
 * One container for the whole task rather than per class, which would spend a minute
 * waiting for Postgres to boot over and over.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class PostgresTest {

    companion object {
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:17")
            .withDatabaseName("b2b")
            .withUsername("b2b")
            .withPassword("b2b")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

/** @DataJpaTest needs a configuration to find, and a library module has none. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class])
@EntityScan("com.acme.b2b.infrastructure.persistence.entity")
@EnableJpaRepositories("com.acme.b2b.infrastructure.persistence.jpa")
class IntegrationTestApp
