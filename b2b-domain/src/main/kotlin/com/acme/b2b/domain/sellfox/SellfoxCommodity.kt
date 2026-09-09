package com.acme.b2b.domain.sellfox

/**
 * One commodity as Sellfox describes it, reduced to the fields the portal uses.
 *
 * Deliberately not the wire shape: Sellfox returns ~60 fields per row, most of them
 * customs-declaration paperwork, and every one carried into the domain is a field
 * something here could come to depend on.
 */
data class SellfoxCommodity(
    val commodityId: String,
    val sku: String,
    val name: String,
    /** Path of category ids as Sellfox reports it, e.g. "100010-100020-100030-". */
    val fullCid: String,
    /** Path of category names, e.g. "供应商甲/重卡配件/轮毂盖". */
    val fullName: String,
    val weightGrams: Double?,
    /**
     * What this SKU is made of. Empty for a plain SKU; one entry for a pack; more than
     * one for a kit. Sellfox's own `isGroup` flag is not used — it does not distinguish
     * a pack from a kit, and the child list does.
     */
    val children: List<SellfoxChild>,
    /** Sellfox lifecycle state; 1 is active. */
    val state: String,
) {
    val isActive: Boolean get() = state == ACTIVE_STATE

    private companion object {
        const val ACTIVE_STATE = "1"
    }
}

data class SellfoxChild(val sku: String, val quantity: Int)

/** Stock for one SKU at one warehouse. */
data class SellfoxStock(
    val sku: String,
    val warehouseId: Long,
    /** On hand and sellable here. */
    val available: Int,
    /** In transit to here — Sellfox calls it stockWait. */
    val incoming: Int,
)

data class SellfoxWarehouse(
    val warehouseId: Long,
    val name: String,
    /** 0 default, 1 domestic, 2 FBA, 3 overseas. */
    val type: Int?,
)
