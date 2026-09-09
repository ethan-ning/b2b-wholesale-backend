package com.acme.b2b.types

/**
 * Identifies a purchasable unit beneath an SPU: the SPU code plus the variant suffix,
 * e.g. "GL100-BLK-M" (a size) or "PL001-BLK-06" (a pack quantity).
 */
@JvmInline
value class SkuCode(val value: String) {
    init {
        require(value.isNotBlank()) { "SKU code must not be blank" }
        require(value.length <= 80) { "SKU code must be 80 characters or fewer" }
        require(PATTERN.matches(value)) { "SKU code must be upper-case alphanumerics and dashes: $value" }
    }

    /** True when this SKU sits beneath [spu]. The suffix carries the variant value. */
    fun belongsTo(spu: SpuCode): Boolean = value.startsWith("${spu.value}-")

    /** The variant suffix — "M", "XL", "06" — or null if this is not under [spu]. */
    fun variantSuffix(spu: SpuCode): String? =
        if (belongsTo(spu)) value.removePrefix("${spu.value}-") else null

    override fun toString() = value

    companion object {
        private val PATTERN = Regex("^[A-Z0-9]+(-[A-Z0-9]+)*$")
    }
}
