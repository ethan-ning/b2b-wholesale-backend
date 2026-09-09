package com.acme.b2b.types

/** Identifies a dealer pricing tier (Gold, Silver, ...). */
@JvmInline
value class TierId(val value: Long) {
    init { require(value > 0) { "Tier id must be positive, was $value" } }
    override fun toString() = value.toString()
}
