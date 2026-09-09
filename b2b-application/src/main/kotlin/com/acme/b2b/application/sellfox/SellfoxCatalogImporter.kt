package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.sellfox.SellfoxCatalogPort
import com.acme.b2b.domain.sellfox.SellfoxCategoryScope
import com.acme.b2b.domain.sellfox.SellfoxCommodity
import com.acme.b2b.domain.sellfox.SellfoxScopeRepository
import com.acme.b2b.domain.sellfox.SellfoxSkuLink
import com.acme.b2b.domain.sellfox.SellfoxSkuLinkRepository
import com.acme.b2b.domain.sellfox.SyncCounts
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Records what Sellfox says. Nothing more.
 *
 * This step does not group and does not touch a product. It fetches the catalog, refreshes
 * the category registry, and writes one row per in-scope SKU describing what the commodity
 * declared — its SPU if it has one, the SKU it packs and how many, its name and weight.
 *
 * Keeping it to facts is what leaves [SpuRegrouper] the only code that decides how SKUs
 * relate; two places deciding that would mean one of them always holding a stale answer.
 *
 * The registry is rebuilt from the scan because Sellfox exposes no category endpoint —
 * every commodity carries the full path of ids and names, so the tree is only recoverable
 * from the commodities themselves. That is why a first run on a fresh install records
 * nothing and simply fills the picker.
 */
@Component
class SellfoxCatalogImporter(
    private val sellfox: SellfoxCatalogPort,
    private val scope: SellfoxScopeRepository,
    private val links: SellfoxSkuLinkRepository,
) {

    fun recordFacts(counts: SyncCounts, now: Instant): String {
        val commodities = sellfox.listCommodities()
        counts.read(commodities.size)

        scope.refreshCategories(discoverCategories(commodities), now)

        val selected = scope.selectedCategoryIds()
        if (selected.isEmpty()) {
            counts.skipped(commodities.size)
            return "Discovered ${scope.categories().size} category groups; none selected, so nothing recorded."
        }

        val inScope = commodities.filter { it.isActive && groupKeyOf(it.fullCid) in selected }
        inScope.forEach { links.save(it.toLink(now)) }

        // Whatever this run did not see has left the scope. Forgetting it here is what
        // lets the regroup step deactivate the products it used to hold: it groups the
        // links that survive, and a product with none left is out of scope.
        val forgotten = links.deleteSkusNotIn(inScope.map { it.sku }.toSet())

        counts.wrote(inScope.size)
        counts.skipped(commodities.size - inScope.size)

        return buildString {
            append("${inScope.size} SKUs recorded from ${selected.size} categor")
            append(if (selected.size == 1) "y" else "ies")
            if (forgotten > 0) append(", $forgotten no longer in scope")
            append(".")
        }
    }

    private fun SellfoxCommodity.toLink(now: Instant): SellfoxSkuLink {
        val child = children.singleOrNull()
        return SellfoxSkuLink(
            sellfoxSku = sku,
            commodityId = commodityId,
            fullCid = fullCid,
            declaredSpu = declaredSpu,
            baseSellfoxSku = child?.sku,
            baseQuantity = child?.quantity,
            commodityName = name,
            weightGrams = weightGrams,
            lastSeenAt = now,
        )
    }

    private fun discoverCategories(commodities: List<SellfoxCommodity>): List<SellfoxCategoryScope> =
        commodities
            .filter { it.fullCid.isNotBlank() }
            .groupBy { groupKeyOf(it.fullCid) }
            .map { (key, rows) ->
                SellfoxCategoryScope(
                    cid = key,
                    fullCid = key,
                    fullName = groupNameOf(rows.first().fullName),
                    // Everything beneath the group, since that is what selecting it takes.
                    commodityCount = rows.size,
                )
            }

    /**
     * The group a commodity belongs to: the first two levels of its path, so
     * "100010-100020-100030-" groups under "100010-100020". A one-level path is its own
     * group rather than being dropped — "未分类" has nothing beneath it and still holds
     * commodities someone may want.
     */
    private fun groupKeyOf(fullCid: String): String =
        fullCid.trim('-').split('-').take(GROUP_DEPTH).joinToString("-")

    /** "供应商甲/重卡配件/轮毂盖" reads as "供应商甲/重卡配件". */
    private fun groupNameOf(fullName: String): String =
        fullName.split('/').take(GROUP_DEPTH).joinToString("/")

    private companion object {
        /** Categories are chosen two levels down — see SellfoxCategoryScope. */
        const val GROUP_DEPTH = 2
    }
}
