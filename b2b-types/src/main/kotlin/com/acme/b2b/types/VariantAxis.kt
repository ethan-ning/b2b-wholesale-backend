package com.acme.b2b.types

/**
 * The single dimension an SPU's SKUs vary along. One axis per SPU keeps the variant
 * table a flat list rather than a matrix; colour is an SPU distinction, not an axis.
 */
enum class VariantAxis(val label: String) {
    SIZE("Size"),
    PACK_QUANTITY("Pack Qty");

    companion object {
        fun fromLabel(label: String): VariantAxis =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown variant axis: $label")
    }
}
