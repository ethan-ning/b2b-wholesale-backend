package com.acme.b2b.types

/**
 * Identifies a product (a style/colour), e.g. "PL001-BLK" or "GL100-BLK".
 * Validation lives in the constructor, so an invalid code cannot exist.
 */
@JvmInline
value class SpuCode(val value: String) {
    init {
        require(value.isNotBlank()) { "SPU code must not be blank" }
        require(value.length <= 64) { "SPU code must be 64 characters or fewer" }
        require(PATTERN.matches(value)) { "SPU code must be upper-case alphanumerics and dashes: $value" }
    }

    override fun toString() = value

    companion object {
        /** Same shape as a SKU code — an SPU is derived from the SKUs beneath it. */
        private val PATTERN = Regex("^[A-Za-z0-9]+([A-Za-z0-9 .+_/-]*[A-Za-z0-9])?$")
    }
}
