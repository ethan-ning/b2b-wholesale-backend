package com.acme.b2b.domain.customer

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.types.Email
import com.acme.b2b.types.TierId

interface CustomerRepository {
    fun findById(id: Long): Customer?
    fun findByEmail(email: Email): Customer?
    fun existsByEmail(email: Email): Boolean
    fun search(criteria: CustomerSearchCriteria, page: Page): PageOf<Customer>
    fun save(customer: Customer): Customer
    /** True when any dealer is on this tier — a tier in use must not be deleted. */
    fun anyOnTier(tierId: TierId): Boolean
}

interface CustomerTierRepository {
    fun findById(id: TierId): CustomerTier?
    fun findAll(): List<CustomerTier>
}
