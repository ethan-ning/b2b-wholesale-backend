package com.acme.b2b.types

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The secret inside a password-reset link.
 *
 * Only ever exists in two places: the email that carries it, and the request that spends
 * it. What the database holds is [digest], so reading the table does not let anyone take
 * over an account.
 *
 * The digest is a plain SHA-256 rather than BCrypt on purpose. A reset row has to be found
 * *by* the token the link carries, and a salted hash cannot be looked up. The trade is
 * acceptable where a password's would not be: the token is 256 bits of randomness with no
 * structure to guess, single-use, and short-lived, so there is nothing for a fast hash to
 * make brute-forceable.
 */
@JvmInline
value class ResetToken(val value: String) {
    init {
        require(value.length >= MIN_LENGTH) { "Reset token is too short to be one we issued" }
    }

    /** What gets stored and matched on. Lower-case hex, so the column is a fixed-length VARCHAR(64). */
    val digest: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Never let a token reach a log through an interpolated string. */
    override fun toString() = "ResetToken(****)"

    companion object {
        /** 32 bytes base64url-encoded, so a shorter value cannot be one of ours. */
        const val MIN_LENGTH = 40

        private val RANDOM = SecureRandom()

        fun random(): ResetToken {
            val bytes = ByteArray(32).also(RANDOM::nextBytes)
            return ResetToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}
