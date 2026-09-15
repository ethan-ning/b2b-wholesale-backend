package com.acme.b2b.domain.auth

import com.acme.b2b.types.Email

/**
 * Sending the reset link. A port, like [com.acme.b2b.domain.auth.PasswordHasher] and the
 * image store: the domain states what has to leave the process, infrastructure decides
 * how — SMTP in production, a log line locally.
 *
 * Deliberately not a general "send an email" interface. One method with named arguments
 * cannot be called with the subject and body the wrong way round, and there is exactly
 * one message this system sends.
 */
interface ResetLinkMailer {

    /**
     * Send the link. Implementations must not throw on a delivery failure — the caller
     * has already committed a token and must return the same answer whether or not the
     * address exists. Log and move on.
     */
    fun sendResetLink(message: ResetLinkMessage)
}

/**
 * Everything the message needs. [link] is fully formed by the caller: building a URL means
 * knowing the portal's public address, which is configuration, not a mail concern.
 */
data class ResetLinkMessage(
    val to: Email,
    /** How to address them. The account's name, not their email. */
    val recipientName: String,
    val link: String,
    val expiresInMinutes: Long,
    /** Dealers and admins get the same mechanism but not quite the same words. */
    val audience: ResetAudience,
)
