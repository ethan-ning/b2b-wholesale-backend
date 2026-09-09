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
        require(PATTERN.matches(value)) { "SKU code must be upper-case alphanumerics, dashes and spaces: $value" }
    }

    /**
     * True when this SKU sits beneath [spu], either as a suffixed variant or as the
     * product's only SKU. The equal case is real: the single-unit SKU that packs are
     * built from names the whole family, and there is no suffix to add to it.
     */
    fun belongsTo(spu: SpuCode): Boolean =
        value == spu.value || value.startsWith("${spu.value}-")

    /** The variant suffix — "M", "XL", "06" — or null when there is none. */
    fun variantSuffix(spu: SpuCode): String? =
        if (value.startsWith("${spu.value}-")) value.removePrefix("${spu.value}-") else null

    override fun toString() = value

    companion object {
        /**
         * Spaces are allowed inside the code, not at its ends. Supplier SKUs really do
         * carry them — "AX-K210-ZN-4" and "AX-K210-ZN-4 S" are different products — and
         * normalising them away silently maps one product onto another.
         */
        private val PATTERN = Regex("^[A-Z0-9]+([A-Z0-9 -]*[A-Z0-9])?$")
    }
}
