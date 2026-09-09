package com.acme.b2b.types

/**
 * A hashed password. Exists so a plaintext password and a hashed one cannot be
 * confused at a call site, and so a hash cannot be logged by accident — `toString`
 * deliberately does not reveal it.
 */
@JvmInline
value class PasswordHash(val value: String) {
    init { require(value.isNotBlank()) { "Password hash must not be blank" } }

    override fun toString() = "PasswordHash(****)"
}

/**
 * A plaintext password on its way to being hashed or verified. Never persisted.
 * Carries the length rule, so "too short" is caught at construction rather than
 * somewhere in a service.
 */
@JvmInline
value class RawPassword(val value: String) {
    init {
        require(value.length >= MIN_LENGTH) { "Password must be at least $MIN_LENGTH characters" }
        require(value.length <= MAX_LENGTH) { "Password must be $MAX_LENGTH characters or fewer" }
    }

    override fun toString() = "RawPassword(****)"

    companion object {
        const val MIN_LENGTH = 8
        /** BCrypt silently truncates beyond 72 bytes; reject rather than truncate. */
        const val MAX_LENGTH = 72
    }
}
