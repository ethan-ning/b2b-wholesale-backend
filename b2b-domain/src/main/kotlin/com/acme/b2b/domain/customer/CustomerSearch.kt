package com.acme.b2b.domain.customer

/** What an admin is filtering the dealer list by. */
data class CustomerSearchCriteria(
    /** Matched against name, email and company. */
    val text: String? = null,
    val status: CustomerStatus? = null,
)
