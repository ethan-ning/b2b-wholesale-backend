package com.acme.b2b.application.sellfox

import com.acme.b2b.domain.catalog.Product
import com.acme.b2b.domain.catalog.ProductRepository
import com.acme.b2b.domain.catalog.ProductStatus
import com.acme.b2b.domain.catalog.ProductVariant
import com.acme.b2b.domain.catalog.StockLevel
import com.acme.b2b.domain.sellfox.*
import com.acme.b2b.types.Money
import com.acme.b2b.types.PackQuantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import com.acme.b2b.types.VariantAxis
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/**
 * Brings the catalog in line with Sellfox.
 *
 * The scan is unavoidably whole-catalog: the commodity endpoint accepts no category
 * filter — every parameter name for one was tried against the live API and ignored — so
 * scoping happens here, after the rows are in hand. At ~6,400 commodities that is a
 * minute of paced paging, which is why this runs nightly rather than every quarter hour.
 *
 * The run does two separable things and always both: it records every category it saw,
 * which is the only way an admin can be offered a list to choose from, and it imports
 * the categories already chosen. On a fresh install nothing is chosen, so the first run
 * imports nothing and fills the picker — safe by construction rather than by warning.
 */
@Service
class SellfoxCatalogSyncService(
    private val sellfox: SellfoxCatalogPort,
    private val scope: SellfoxScopeRepository,
    private val products: ProductRepository,
    private val links: SellfoxSkuLinkRepository,
    private val runner: SyncRunner,
    private val clock: Clock,
) {

    fun sync(trigger: TriggerSource, triggeredBy: String? = null): SellfoxSyncRun =
        runner.run(SellfoxJob.CATALOG, trigger, triggeredBy) { counts ->
            val now = clock.instant()
            val commodities = sellfox.listCommodities()
            counts.read(commodities.size)

            scope.refreshCategories(discoverCategories(commodities), now)

            val selected = scope.selectedCategoryIds()
            if (selected.isEmpty()) {
                counts.skipped(commodities.size)
                return@run "Discovered ${scope.categories().size} categories. " +
                    "None selected yet, so nothing was imported."
            }

            val families = SpuGrouping.group(commodities) { leafCidOf(it.fullCid) in selected }
            val outcome = families.map { importFamily(it, now) }

            counts.wrote(outcome.count { it != Outcome.FAILED })
            counts.skipped(commodities.size - families.sumOf { it.members.size })

            val created = outcome.count { it == Outcome.CREATED }
            val updated = outcome.count { it == Outcome.UPDATED }
            val failed = outcome.count { it == Outcome.FAILED }
            buildString {
                append("${families.size} products across ${selected.size} categories: ")
                append("$created created, $updated updated")
                if (failed > 0) append(", $failed could not be built")
            }
        }

    /**
     * The category registry, rebuilt from what the scan saw. Sellfox exposes no category
     * endpoint, but every commodity carries the full path of ids and names, so the tree
     * is recoverable from the commodities themselves.
     */
    private fun discoverCategories(commodities: List<SellfoxCommodity>): List<SellfoxCategoryScope> =
        commodities
            .filter { it.fullCid.isNotBlank() }
            .groupBy { it.fullCid }
            .map { (fullCid, rows) ->
                SellfoxCategoryScope(
                    cid = leafCidOf(fullCid),
                    fullCid = fullCid,
                    fullName = rows.first().fullName,
                    commodityCount = rows.size,
                )
            }

    /** "100010-100020-100030-" identifies its leaf, 100030 — the id an admin selects. */
    private fun leafCidOf(fullCid: String): String = fullCid.trim('-').substringAfterLast('-')

    private fun importFamily(family: SpuGrouping.Family, now: Instant): Outcome {
        val product = try {
            buildProduct(family)
        } catch (ex: IllegalArgumentException) {
            // A SKU the domain refuses — an unexpected character, most likely. Counted
            // rather than thrown: one bad row must not abandon the other six thousand,
            // and the run's summary reports how many were dropped.
            return Outcome.FAILED
        }

        val existing = products.findBySpuCode(product.spuCode)
        val saved = if (existing == null) products.create(product) else products.saveSynced(product, existing)

        family.members.forEach { member ->
            links.save(
                SellfoxSkuLink(
                    sellfoxSku = member.commodity.sku,
                    commodityId = member.commodity.commodityId,
                    fullCid = member.commodity.fullCid,
                    baseSellfoxSku = if (member.isBase) null else family.members.firstOrNull { it.isBase }
                        ?.commodity?.sku,
                    lastSeenAt = now,
                )
            )
        }
        return if (existing == null) Outcome.CREATED else Outcome.UPDATED
    }

    private fun buildProduct(family: SpuGrouping.Family): Product {
        val spu = SpuCode(family.spuCode)
        val variants = family.members.mapIndexed { index, member ->
            val sku = SkuCode(member.commodity.sku)
            ProductVariant(
                id = null,
                sku = sku,
                variantValue = member.variantValue,
                packQuantity = PackQuantity(member.packQuantity),
                // MAP and price are the portal's to set; Sellfox has neither.
                mapPrice = null,
                upc = null,
                weight = member.commodity.weightGrams?.let { grams ->
                    BigDecimal.valueOf(grams).divide(GRAMS_PER_KILO, 3, RoundingMode.HALF_UP)
                },
                sortOrder = index,
                active = true,
                stock = StockLevel.empty(clock.instant()),
            )
        }

        return Product(
            id = null,
            spuCode = spu,
            name = family.name,
            brand = null,
            description = null,
            // No dealer price exists yet. Importing at zero and leaving the product
            // INACTIVE is what keeps an unpriced product out of the catalog; the admin
            // prices it and turns it on.
            baseWholesalePrice = Money.ZERO,
            locationCode = null,
            variantAxis = when (family.axis) {
                SpuGrouping.Axis.PACK_QUANTITY -> VariantAxis.PACK_QUANTITY
                SpuGrouping.Axis.SIZE -> VariantAxis.SIZE
                null -> null
            },
            attributes = emptyMap(),
            status = ProductStatus.INACTIVE,
            categoryIds = emptyList(),
            primaryCategoryId = null,
            imageUrls = emptyList(),
            variants = variants,
        )
    }

    private enum class Outcome { CREATED, UPDATED, FAILED }

    private companion object {
        val GRAMS_PER_KILO: BigDecimal = BigDecimal(1000)
    }
}
