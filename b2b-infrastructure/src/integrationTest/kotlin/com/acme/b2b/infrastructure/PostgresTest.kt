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
 * These tests exist for the things a fake cannot be wrong about in the same way: whether
 * `V1__schema.sql` actually applies, whether Hibernate's mappings match the tables it
 * validates against, whether a cascade or a flush ordering behaves as the code assumes.
 * Everything else is a unit test — see `./gradlew test`.
 *
 * The container is started once for the whole task and left to Ryuk to reap, rather than
 * per class. Twelve classes each waiting for Postgres to boot is a minute of nothing.
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

/**
 * A context for the tests to live in. b2b-infrastructure has no application class of its
 * own — it is a library — so @DataJpaTest has nothing to find without this.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class])
@EntityScan("com.acme.b2b.infrastructure.persistence.entity")
@EnableJpaRepositories("com.acme.b2b.infrastructure.persistence.jpa")
class IntegrationTestApp
