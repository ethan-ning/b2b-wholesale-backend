package com.acme.b2b.domain.auth

/**
 * Port. A login use case needs to hand back a session token; nothing above this
 * interface should know it is a JWT, nor how it is signed.
 *
 * It lives in the domain rather than the application layer so that infrastructure —
 * which implements it — depends only on the domain. An adapter reaching up into the
 * application layer would invert the dependency rule.
 */
interface AccessTokenIssuer {
    fun issueForAdmin(adminId: Long, email: String, role: String): String
    fun issueForDealer(customerId: Long, email: String, tierId: Long): String
}
