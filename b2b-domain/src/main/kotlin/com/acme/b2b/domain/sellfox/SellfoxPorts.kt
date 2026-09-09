package com.acme.b2b.domain.sellfox

import java.time.Instant

/**
 * The Sellfox side, as the application needs it. Implemented in infrastructure over the
 * signed HTTP API; the layers above never see a token, a nonce or a page number.
 */
interface SellfoxCatalogPort {
    /**
     * Every commodity Sellfox holds. Deliberately not filtered server-side: the endpoint
     * ignores every category parameter it was offered, so scoping happens here after the
     * scan. At ~6,400 rows in 100-row pages that is a minute of paging, which is fine for
     * a job that runs nightly and is the reason it does not run more often.
     */
    fun listCommodities(): List<SellfoxCommodity>
}

interface SellfoxInventoryPort {
    fun listWarehouses(): List<SellfoxWarehouse>
    /** Stock rows for one warehouse, all pages. */
    fun listStock(warehouseId: Long): List<SellfoxStock>
}

/** What the admin has chosen to import. Nothing enters the catalog from outside it. */
interface SellfoxScopeRepository {
    fun selectedCategoryIds(): Set<String>
    fun selectedWarehouseIds(): List<Long>

    fun categories(): List<SellfoxCategoryScope>
    fun warehouses(): List<SellfoxWarehouseScope>

    /** Records what a scan saw, leaving each row's `selected` flag alone. */
    fun refreshCategories(seen: List<SellfoxCategoryScope>, at: Instant)
    fun refreshWarehouses(seen: List<SellfoxWarehouse>, at: Instant)

    /**
     * Replaces the whole selection rather than toggling one row. Clearing is then the
     * same operation as choosing, instead of a loop of ninety calls, and two admins
     * editing at once cannot interleave into a selection neither of them made.
     */
    fun selectCategories(cids: Set<String>)
    fun selectWarehouses(warehouseIds: Set<Long>)
}

/**
 * A second-level category group — "供应商甲/重卡配件" — and everything beneath it.
 *
 * The leaves are the wrong unit to choose from: ninety of them, most holding a handful
 * of SKUs, and picking one product line would mean ticking a dozen boxes.
 */
data class SellfoxCategoryScope(
    /** First two segments of the path's ids, joined by "-". */
    val cid: String,
    val fullCid: String,
    /** First two segments of the path's names. */
    val fullName: String,
    /** Commodities anywhere beneath this group, not just directly in it. */
    val commodityCount: Int,
    val selected: Boolean = false,
    val lastSeenAt: Instant? = null,
)

data class SellfoxWarehouseScope(
    val warehouseId: Long,
    val name: String,
    val type: Int?,
    val selected: Boolean,
    val lastSeenAt: Instant?,
)

/** Where an imported SKU came from, and how it was grouped. */
interface SellfoxSkuLinkRepository {
    fun save(link: SellfoxSkuLink)
    fun findAll(): List<SellfoxSkuLink>
    fun skusFor(fullCids: Set<String>): Set<String>
}

/**
 * What a sync learned about one SKU, kept so the grouping can be recomputed without
 * asking Sellfox again.
 *
 * These are the *inputs* to grouping, not its results. Reading pack quantity back off the
 * variant would make each regroup depend on the answer the last one gave.
 */
data class SellfoxSkuLink(
    val sellfoxSku: String,
    val commodityId: String,
    val fullCid: String,
    /** Sellfox's own SPU for this SKU, where it had one. */
    val declaredSpu: String?,
    /** The single-unit SKU this one packs; null when it is itself the base. */
    val baseSellfoxSku: String?,
    /** How many of [baseSellfoxSku] this SKU holds. */
    val baseQuantity: Int?,
    val commodityName: String,
    val lastSeenAt: Instant,
)
