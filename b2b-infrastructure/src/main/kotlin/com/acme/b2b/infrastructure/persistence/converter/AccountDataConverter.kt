package com.acme.b2b.infrastructure.persistence.converter

import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.customer.Customer
import com.acme.b2b.domain.customer.CustomerStatus
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.infrastructure.persistence.entity.AdminUserDO
import com.acme.b2b.infrastructure.persistence.entity.CustomerDO
import com.acme.b2b.infrastructure.persistence.entity.CustomerTierDO
import com.acme.b2b.types.*
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AccountDataConverter {

    fun toDomain(row: AdminUserDO) = AdminUser(
        id = row.id,
        email = Email(row.email),
        passwordHash = PasswordHash(row.passwordHash),
        name = row.name,
        role = AdminRole.valueOf(row.role),
    )

    fun toDomain(row: CustomerDO) = Customer(
        id = row.id,
        email = Email(row.email),
        passwordHash = PasswordHash(row.passwordHash),
        name = row.name,
        companyName = row.companyName,
        tierId = TierId(row.tierId),
        phone = row.phone,
        status = CustomerStatus.valueOf(row.status),
        mustChangePassword = row.mustChangePassword,
        createdAt = row.createdAt,
    )

    /**
     * Writes onto an existing row so JPA sees an update rather than an insert. Email is
     * written only on creation: changing it is an identity change with its own use case,
     * and letting a profile edit through here would make that silently possible.
     */
    fun applyTo(row: CustomerDO, customer: Customer): CustomerDO {
        if (row.id == null) {
            row.email = customer.email.value
            row.createdAt = Instant.now()
        }
        row.passwordHash = customer.passwordHash.value
        row.name = customer.name
        row.companyName = customer.companyName
        row.tierId = customer.tierId.value
        row.phone = customer.phone
        row.status = customer.status.name
        row.mustChangePassword = customer.mustChangePassword
        row.updatedAt = Instant.now()
        return row
    }

    fun toDomain(row: CustomerTierDO) = CustomerTier(
        id = TierId(checkNotNull(row.id) { "A persisted tier must have an id" }),
        name = row.name,
        sortOrder = row.sortOrder,
    )
}
