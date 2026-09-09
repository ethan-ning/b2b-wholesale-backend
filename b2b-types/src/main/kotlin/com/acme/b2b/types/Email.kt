package com.acme.b2b.types

/**
 * An email address, normalised to lower case so lookups and uniqueness behave.
 * Validation is deliberately shallow — the only real proof an address works is
 * sending to it, and over-strict patterns reject valid addresses.
 */
@JvmInline
value class Email(val value: String) {
    init {
        require(value.isNotBlank()) { "Email must not be blank" }
        require(value.length <= 254) { "Email must be 254 characters or fewer" }
        require(PATTERN.matches(value)) { "Not a valid email address: $value" }
    }

    override fun toString() = value

    companion object {
        private val PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** Normalises before validating, so callers need not remember to. */
        fun of(raw: String) = Email(raw.trim().lowercase())
    }
}
