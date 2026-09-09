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

    fun setCategorySelected(cid: String, selected: Boolean)
    fun setWarehouseSelected(warehouseId: Long, selected: Boolean)
}

data class SellfoxCategoryScope(
    val cid: String,
    val fullCid: String,
    val fullName: String,
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

data class SellfoxSkuLink(
    val sellfoxSku: String,
    val commodityId: String,
    val fullCid: String,
    /** The single-unit SKU this one packs; null when it is itself the base. */
    val baseSellfoxSku: String?,
    val lastSeenAt: Instant,
)
