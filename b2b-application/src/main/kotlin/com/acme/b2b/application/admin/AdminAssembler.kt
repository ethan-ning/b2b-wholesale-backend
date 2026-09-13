package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.AdminUserDTO
import com.acme.b2b.application.admin.dto.CustomerDTO
import com.acme.b2b.application.admin.dto.CustomerTierDTO
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.customer.Customer
import com.acme.b2b.domain.customer.CustomerTier

object AdminAssembler {

    fun toDTO(admin: AdminUser) = AdminUserDTO(
        id = admin.id,
        email = admin.email.value,
        name = admin.name,
        role = admin.role.name,
        mustChangePassword = admin.mustChangePassword,
    )

    /**
     * Tier name is resolved by the caller rather than loaded per customer — a page of
     * 10 dealers would otherwise be 10 extra queries.
     */
    fun toDTO(customer: Customer, tierNames: Map<Long, String>) = CustomerDTO(
        id = customer.id,
        email = customer.email.value,
        name = customer.name,
        companyName = customer.companyName,
        tierId = customer.tierId.value,
        tierName = tierNames[customer.tierId.value] ?: "",
        phone = customer.phone,
        status = customer.status.name,
        mustChangePassword = customer.mustChangePassword,
        createdAt = customer.createdAt?.toString(),
    )

    fun toDTO(tier: CustomerTier) = CustomerTierDTO(
        id = tier.id.value,
        name = tier.name,
        sortOrder = tier.sortOrder,
        discountPercent = tier.discount.value,
    )
}
