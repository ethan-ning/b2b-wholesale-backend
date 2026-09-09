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

    /**
     * A token that can do one thing: set a password. Issued when a dealer must replace the
     * one an admin generated for them, so "forced change" is enforced by what the token is
     * allowed to reach rather than by the client agreeing to navigate somewhere.
     */
    fun issuePasswordChangeToken(customerId: Long, email: String): String
}
