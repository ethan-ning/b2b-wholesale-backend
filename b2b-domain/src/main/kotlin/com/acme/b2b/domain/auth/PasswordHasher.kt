package com.acme.b2b.domain.auth

import com.acme.b2b.types.PasswordHash
import com.acme.b2b.types.RawPassword

/**
 * Port. The domain needs to know whether a password is correct; it must not know
 * whether that is BCrypt, Argon2 or anything else. Infrastructure supplies the
 * algorithm, so changing it never reaches an entity or a use case.
 */
interface PasswordHasher {
    fun hash(raw: RawPassword): PasswordHash

    /** Constant-time in any sane implementation — do not reduce this to `==`. */
    fun matches(raw: RawPassword, hash: PasswordHash): Boolean
}

/**
 * Generates the temporary password an admin-created dealer receives. A port because
 * "how random, and drawn from which alphabet" is a security decision, not a
 * business rule.
 */
interface TemporaryPasswordGenerator {
    fun generate(): RawPassword
}
