package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.catalog.ProductGroupingRepository
import com.acme.b2b.domain.catalog.ProductVisibility
import com.acme.b2b.domain.catalog.ProductStockRepository
import com.acme.b2b.domain.catalog.RegroupOutcome
import com.acme.b2b.domain.catalog.RegroupedFamily
import com.acme.b2b.domain.catalog.SkuStockUpdate
import com.acme.b2b.domain.catalog.VariantStockBreakdownRepository
import com.acme.b2b.domain.catalog.WarehouseStockLine
import com.acme.b2b.domain.catalog.RegroupedSku
import com.acme.b2b.infrastructure.persistence.entity.ProductDO
import com.acme.b2b.infrastructure.persistence.entity.ProductVariantDO
import com.acme.b2b.infrastructure.persistence.entity.VariantWarehouseStockDO
import com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.VariantWarehouseStockJpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Stock writes, straight at the variant rows. No product aggregate is loaded: a quarter-
 * hourly job touching a few thousand SKUs cannot afford to hydrate a few thousand
 * aggregates to change two integers on each.
 */
@Repository
class ProductStockRepositoryImpl(
    private val variants: ProductVariantJpaRepository,
    private val warehouseStock: VariantWarehouseStockJpaRepository,
) : ProductStockRepository {

    @Transactional
    override fun applyStock(updates: List<SkuStockUpdate>): Int {
        if (updates.isEmpty()) return 0
        val bySku = updates.associateBy { it.sku }
        val rows = variants.findBySkuIn(bySku.keys)
        rows.forEach { row ->
            val update = bySku.getValue(row.sku)
            row.availableStock = update.available
            row.incomingStock = update.incoming
            row.stockSyncedAt = update.syncedAt
        }
        variants.saveAll(rows)

        // Replaced rather than merged, and flushed between: a warehouse that has left the
        // scope contributes nothing to the new total, so its old row must not survive to
        // suggest otherwise.
        val matchedSkus = rows.map { it.sku }.toSet()
        if (matchedSkus.isNotEmpty()) {
            warehouseStock.deleteBySkuIn(matchedSkus)
            warehouseStock.flush()
            warehouseStock.saveAll(
                matchedSkus.flatMap { sku ->
                    val update = bySku.getValue(sku)
                    update.byWarehouse.map { line ->
                        VariantWarehouseStockDO(sku, line.warehouseId, line.available, line.incoming, update.syncedAt)
                    }
                }
            )
        }
        return rows.size
    }
}

/** The breakdown, read back for the admin product detail view. */
@Repository
class VariantStockBreakdownRepositoryImpl(
    private val warehouseStock: VariantWarehouseStockJpaRepository,
) : VariantStockBreakdownRepository {

    override fun findBySkus(skus: List<String>): List<WarehouseStockLine> {
        if (skus.isEmpty()) return emptyList()
        return warehouseStock.findLinesBySkuIn(skus).map { row ->
            WarehouseStockLine(
                sku = row[0] as String,
                warehouseId = row[1] as Long,
                warehouseName = row[2] as String,
                available = row[3] as Int,
                incoming = row[4] as Int,
                syncedAt = row[5] as Instant,
            )
        }
    }
}

/**
 * Materialises the grouping: creates the products and SKUs it names, moves any SKU that
 * has changed product, and clears out whatever is left holding nothing.
 *
 * Row level rather than through the Product aggregate. A SKU moving between products
 * cannot be expressed as saving one aggregate, and the unique constraint on
 * product_variant.sku makes the move a reparent rather than an insert-then-delete.
 */
@Repository
class ProductGroupingRepositoryImpl(
    private val products: ProductJpaRepository,
    private val variants: ProductVariantJpaRepository,
) : ProductGroupingRepository {

    /**
     * One transaction, deliberately: a reparent, an emptied shell removed and unplaced
     * SKUs withdrawn only make sense together.
     *
     * It also settles which persistence context these run in. Left to the caller each
     * repository call got its own, and two assumptions here about what a loaded collection
     * holds were true only by accident of that — hence reading the database below rather
     * than ProductDO.variants, which goes stale the moment this shares one context.
     */
    @Transactional
    override fun regroup(families: List<RegroupedFamily>): RegroupOutcome {
        val bySpu = products.findBySourceIn(listOf(SELLFOX)).associateBy { it.spuCode }.toMutableMap()
        val existingRows = variants
            .findBySkuIn(families.flatMap { family -> family.members.map { it.sku } })
            .associateBy { it.sku }

        var productsCreated = 0
        var skusCreated = 0
        var skusMoved = 0

        families.forEach { family ->
            val target = bySpu[family.spuCode] ?: newProduct(family).also {
                bySpu[family.spuCode] = it
                productsCreated++
            }
            // The supplier names a product once, at creation; after that the name is the
            // portal's. The axis is not the same kind of thing — it is structure this
            // grouping just derived, not a description someone wrote.
            target.variantAxis = family.axis?.label
            target.updatedAt = Instant.now()
            products.save(target)

            family.members.forEach { member ->
                val row = existingRows[member.sku]
                if (row == null) {
                    variants.save(newVariant(target, member))
                    skusCreated++
                    return@forEach
                }
                if (row.product?.id != target.id) {
                    row.product = target
                    skusMoved++
                }
                row.variantValue = member.variantValue
                row.packQuantity = member.packQuantity
                row.sortOrder = member.sortOrder
                row.weight = member.weight ?: row.weight
                row.status = ACTIVE
                variants.save(row)
            }
        }

        // Flushed before looking for empties: the rows that left are still pending, so a
        // product would otherwise look occupied by SKUs it has lost.
        variants.flush()

        // A SKU in no family is one the last import did not see — the supplier stopped
        // selling it, or its category left the scope. Marked rather than deleted, because
        // its tier prices hang off it and a SKU coming back on sale should find its
        // pricing intact. Nothing else clears these, so without this a withdrawn SKU
        // would stay on the shelf indefinitely.
        val placed = families.flatMap { family -> family.members.map { it.sku } }.toSet()
        val withdrawn = variants
            .findByProductSourceAndStatus(SELLFOX, ACTIVE)
            .filterNot { it.sku in placed }
        withdrawn.forEach { it.status = DISCONTINUED }
        variants.saveAll(withdrawn)

        val stillHolding = variants.productIdsHoldingSkus(SELLFOX).toSet()
        val emptied = products.findBySourceIn(listOf(SELLFOX)).mapNotNull { it.id }.filterNot { it in stillHolding }
        if (emptied.isNotEmpty()) products.deleteByIdIn(emptied)

        return RegroupOutcome(
            productsCreated = productsCreated,
            skusCreated = skusCreated,
            skusMoved = skusMoved,
            skusWithdrawn = withdrawn.size,
            emptyProductsRemoved = emptied.size,
        )
    }

    @Transactional
    override fun deactivateProductsNotIn(spuCodes: Set<String>): Int {
        val stale = products
            .findBySourceAndVisibility(SELLFOX, ProductVisibility.VISIBLE.name)
            .filterNot { it.spuCode in spuCodes }
        stale.forEach {
            it.visibility = ProductVisibility.HIDDEN.name
            it.updatedAt = Instant.now()
        }
        products.saveAll(stale)
        return stale.size
    }

    /** Arrives hidden, like every ERP import: nothing has priced it yet. */
    private fun newProduct(family: RegroupedFamily) = products.save(
        ProductDO(
            spuCode = family.spuCode,
            name = family.name,
            source = SELLFOX,
            visibility = ProductVisibility.HIDDEN.name,
            variantAxis = family.axis?.label,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    )

    private fun newVariant(product: ProductDO, member: RegroupedSku) = ProductVariantDO(
        product = product,
        sku = member.sku,
        variantValue = member.variantValue,
        packQuantity = member.packQuantity,
        sortOrder = member.sortOrder,
        weight = member.weight,
        status = ACTIVE,
    )

    private companion object {
        const val SELLFOX = "SELLFOX"
        const val ACTIVE = "ACTIVE"
        const val DISCONTINUED = "DISCONTINUED"
    }
}
