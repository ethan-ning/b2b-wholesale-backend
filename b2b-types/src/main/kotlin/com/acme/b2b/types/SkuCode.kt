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
        require(PATTERN.matches(value)) { "SKU code has characters a supplier code would not: $value" }
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
         * Alphanumerics at the ends; spaces, dashes, dots and plus signs allowed between.
         *
         * The set is what supplier codes actually contain, not what would be tidy. Spaces
         * distinguish real products ("AX-K210-ZN-4" from "AX-K210-ZN-4 S"). A plus marks
         * a kit ("NDR-CPH01+RB-QA118-BLACK-24"). Lower case turns up in a handful
         * ("MRL1001-5p"). Every character rejected here is a product that cannot be
         * imported at all, and codes are stored and matched verbatim, so admitting them
         * costs nothing — nothing normalises case or strips anything.
         */
        private val PATTERN = Regex("^[A-Za-z0-9]+([A-Za-z0-9 .+_/-]*[A-Za-z0-9])?$")
    }
}
