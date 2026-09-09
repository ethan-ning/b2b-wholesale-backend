package com.acme.b2b.domain.customer

import com.acme.b2b.types.TierId

interface CustomerRepository {
    fun findById(id: Long): Customer?
    fun findByEmail(email: String): Customer?
    fun save(customer: Customer): Customer
}

interface CustomerTierRepository {
    fun findById(id: TierId): CustomerTier?
    fun findAll(): List<CustomerTier>
}
