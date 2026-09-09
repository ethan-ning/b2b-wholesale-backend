package com.acme.b2b.infrastructure.security

import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.auth.TemporaryPasswordGenerator
import com.acme.b2b.types.PasswordHash
import com.acme.b2b.types.RawPassword
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * The domain's PasswordHasher port, over Spring Security's BCrypt. Changing the
 * algorithm is a change to this class and nothing else — the encoder's own prefix
 * (`$2a$`) records the cost factor per hash, so existing hashes keep verifying.
 */
@Component
class BCryptPasswordHasher : PasswordHasher {

    private val encoder = BCryptPasswordEncoder(COST)

    override fun hash(raw: RawPassword): PasswordHash = PasswordHash(encoder.encode(raw.value))

    override fun matches(raw: RawPassword, hash: PasswordHash): Boolean =
        encoder.matches(raw.value, hash.value)

    private companion object {
        /** Deliberately slow; raise as hardware improves. */
        const val COST = 12
    }
}

/**
 * Temporary passwords for admin-created dealers. Ambiguous characters are left out —
 * these get read aloud and typed by hand, and 0/O confusion turns into a support call.
 */
@Component
class SecureTemporaryPasswordGenerator : TemporaryPasswordGenerator {

    private val random = SecureRandom()

    override fun generate(): RawPassword =
        RawPassword((1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(""))

    private companion object {
        const val LENGTH = 12
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
