package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.catalog.ProductGroupingRepository
import com.acme.b2b.domain.catalog.ProductStatus
import com.acme.b2b.domain.catalog.ProductStockRepository
import com.acme.b2b.domain.catalog.RegroupOutcome
import com.acme.b2b.domain.catalog.RegroupedFamily
import com.acme.b2b.domain.catalog.SkuStockUpdate
import com.acme.b2b.infrastructure.persistence.entity.ProductDO
import com.acme.b2b.infrastructure.persistence.jpa.ProductJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductVariantJpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Stock writes, straight at the variant rows. No product aggregate is loaded: a quarter-
 * hourly job touching a few thousand SKUs cannot afford to hydrate a few thousand
 * aggregates to change two integers on each.
 */
@Repository
class ProductStockRepositoryImpl(
    private val variants: ProductVariantJpaRepository,
) : ProductStockRepository {

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
        return rows.size
    }
}

/**
 * Re-files SKUs when the grouping changes.
 *
 * Works at row level rather than through the Product aggregate: a SKU moving between
 * products cannot be expressed as saving one aggregate, and the unique constraint on
 * product_variant.sku means the move has to be a reparent rather than an insert-then-delete.
 */
@Repository
class ProductGroupingRepositoryImpl(
    private val products: ProductJpaRepository,
    private val variants: ProductVariantJpaRepository,
) : ProductGroupingRepository {

    override fun regroup(
        families: List<RegroupedFamily>,
    ): RegroupOutcome {
        val bySpu = products.findBySourceIn(listOf(SELLFOX)).associateBy { it.spuCode }
        var created = 0
        var moved = 0

        families.forEach { family ->
            val target = bySpu[family.spuCode] ?: run {
                created++
                products.save(
                    ProductDO(
                        spuCode = family.spuCode,
                        name = family.name,
                        source = SELLFOX,
                        // Imported products are inactive until priced, and a product this
                        // run invents has never been priced.
                        status = ProductStatus.INACTIVE.name,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                )
            }
            target.variantAxis = family.axis?.label
            target.name = family.name
            target.updatedAt = Instant.now()
            products.save(target)

            family.members.forEach { member ->
                val row = variants.findBySkuIn(listOf(member.sku)).firstOrNull() ?: return@forEach
                if (row.product?.id != target.id) {
                    row.product = target
                    moved++
                }
                row.variantValue = member.variantValue
                row.packQuantity = member.packQuantity
                row.sortOrder = member.sortOrder
                variants.save(row)
            }
        }

        // Flush the reparenting before looking for empties: the rows that left are still
        // pending, so a product would otherwise still look occupied by SKUs it has lost.
        variants.flush()

        val emptied = products.findBySourceIn(listOf(SELLFOX)).filter { it.variants.isEmpty() }
        products.deleteAll(emptied)

        return RegroupOutcome(
            productsCreated = created,
            skusMoved = moved,
            emptyProductsRemoved = emptied.size,
        )
    }

    private companion object {
        const val SELLFOX = "SELLFOX"
    }
}
