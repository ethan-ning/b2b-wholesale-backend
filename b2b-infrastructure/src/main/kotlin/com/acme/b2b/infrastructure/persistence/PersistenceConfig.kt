package com.acme.b2b.infrastructure.persistence

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Infrastructure owns its own persistence wiring, so the deployable does not need to
 * know that JPA is the mechanism — or where the DO classes live. Swapping this module
 * for another adapter is a dependency change in b2b-start, not an edit to it.
 */
@Configuration
@EnableJpaRepositories(basePackages = ["com.acme.b2b.infrastructure.persistence.jpa"])
@EntityScan(basePackages = ["com.acme.b2b.infrastructure.persistence.entity"])
class PersistenceConfig
