package com.acme.b2b.domain.customer

import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.types.Email
import com.acme.b2b.types.DiscountPercent
import com.acme.b2b.types.TierId

interface CustomerRepository {
    fun findById(id: Long): Customer?
    fun findByEmail(email: Email): Customer?
    fun existsByEmail(email: Email): Boolean
    fun search(criteria: CustomerSearchCriteria, page: Page): PageOf<Customer>
    fun save(customer: Customer): Customer
    /** True when any dealer is on this tier — a tier in use must not be deleted. */
    fun anyOnTier(tierId: TierId): Boolean

    fun countAll(): Long
    fun countByStatus(status: CustomerStatus): Long
}

interface CustomerTierRepository {
    fun findById(id: TierId): CustomerTier?
    fun findAll(): List<CustomerTier>

    /**
     * The tier every other price is worked out from. Exactly one exists — a unique index
     * says so — and pricing cannot proceed without it.
     */
    fun anchor(): CustomerTier

    /** Retuning what a tier pays. The only field of a tier the back office can change. */
    fun updateDiscount(id: TierId, discount: DiscountPercent): CustomerTier
}
