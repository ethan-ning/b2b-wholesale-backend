package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.admin.AdminUserRepository
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.customer.*
import com.acme.b2b.infrastructure.persistence.converter.AccountDataConverter
import com.acme.b2b.infrastructure.persistence.entity.AdminUserDO
import com.acme.b2b.infrastructure.persistence.entity.CustomerDO
import com.acme.b2b.infrastructure.persistence.jpa.AdminUserJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.CustomerJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.CustomerTierJpaRepository
import com.acme.b2b.types.Email
import com.acme.b2b.types.DiscountPercent
import com.acme.b2b.types.TierId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class AdminUserRepositoryImpl(
    private val jpa: AdminUserJpaRepository,
    private val converter: AccountDataConverter,
) : AdminUserRepository {

    override fun findByEmail(email: Email): AdminUser? =
        jpa.findByEmail(email.value)?.let { converter.toDomain(it) }

    override fun findById(id: Long): AdminUser? =
        jpa.findById(id).orElse(null)?.let { converter.toDomain(it) }

    override fun findAll(): List<AdminUser> = jpa.findAll().map { converter.toDomain(it) }

    override fun existsByEmail(email: Email): Boolean = jpa.existsByEmail(email.value)

    override fun save(admin: AdminUser): AdminUser {
        val row = admin.id?.let { jpa.findById(it).orElse(null) } ?: AdminUserDO()
        return converter.toDomain(jpa.save(converter.applyTo(row, admin)))
    }

    override fun deleteById(id: Long) = jpa.deleteById(id)

    override fun countByRole(role: AdminRole): Long = jpa.countByRole(role.name)
}

@Repository
class CustomerRepositoryImpl(
    private val jpa: CustomerJpaRepository,
    private val converter: AccountDataConverter,
) : CustomerRepository {

    override fun findById(id: Long): Customer? =
        jpa.findById(id).orElse(null)?.let { converter.toDomain(it) }

    override fun findByEmail(email: Email): Customer? =
        jpa.findByEmail(email.value)?.let { converter.toDomain(it) }

    override fun existsByEmail(email: Email): Boolean = jpa.existsByEmail(email.value)

    override fun search(criteria: CustomerSearchCriteria, page: Page): PageOf<Customer> {
        val text = criteria.text?.lowercase()?.let { "%$it%" }
        val found = jpa.search(text, criteria.status?.name, PageRequest.of(page.number, page.size))
        return PageOf(
            content = found.content.map { converter.toDomain(it) },
            totalElements = found.totalElements,
            page = page,
        )
    }

    override fun save(customer: Customer): Customer {
        val row = customer.id?.let { jpa.findById(it).orElse(null) } ?: CustomerDO()
        return converter.toDomain(jpa.save(converter.applyTo(row, customer)))
    }

    override fun anyOnTier(tierId: TierId): Boolean = jpa.existsByTierId(tierId.value)

    override fun countAll(): Long = jpa.count()

    override fun countByStatus(status: CustomerStatus): Long = jpa.countByStatus(status.name)
}

@Repository
class CustomerTierRepositoryImpl(
    private val jpa: CustomerTierJpaRepository,
    private val converter: AccountDataConverter,
) : CustomerTierRepository {

    override fun findById(id: TierId): CustomerTier? =
        jpa.findById(id.value).orElse(null)?.let { converter.toDomain(it) }

    override fun findAll(): List<CustomerTier> =
        jpa.findAll().sortedBy { it.sortOrder }.map { converter.toDomain(it) }

    override fun anchor(): CustomerTier =
        findAll().firstOrNull { it.anchor }
            ?: throw IllegalStateException("No anchor tier — nothing can be priced without one")

    @Transactional
    override fun updateDiscount(id: TierId, discount: DiscountPercent): CustomerTier {
        val row = jpa.findById(id.value).orElseThrow { NoSuchElementException("No such tier") }
        row.discountPercent = discount.value
        return converter.toDomain(jpa.save(row))
    }
}
