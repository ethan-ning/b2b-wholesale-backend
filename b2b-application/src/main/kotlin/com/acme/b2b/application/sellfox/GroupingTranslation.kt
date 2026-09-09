package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.sellfox.SpuGrouping
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.VariantAxis

/**
 * Between what the grouping calculation produces and what the catalog stores. Shared by
 * the importer and the regrouper, which build products from the same families.
 */

/**
 * True when the derived code is one the catalog will accept. Grouping works in plain
 * strings so it can stay pure, and a supplier code it assembled may still be one SpuCode
 * refuses.
 */
internal fun SpuGrouping.Family.hasUsableCode(): Boolean =
    runCatching { SpuCode(spuCode) }.isSuccess

internal fun SpuGrouping.Axis.toVariantAxis(): VariantAxis = when (this) {
    SpuGrouping.Axis.PACK_QUANTITY -> VariantAxis.PACK_QUANTITY
    SpuGrouping.Axis.SIZE -> VariantAxis.SIZE
}
