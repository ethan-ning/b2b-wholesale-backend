package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.pricing.TierPrice
import com.acme.b2b.domain.pricing.TierPriceRepository
import com.acme.b2b.infrastructure.persistence.entity.TierPriceDO
import com.acme.b2b.infrastructure.persistence.jpa.TierPriceJpaRepository
import com.acme.b2b.types.Money
import com.acme.b2b.types.Quantity
import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.TierId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class TierPriceRepositoryImpl(
    private val jpa: TierPriceJpaRepository,
) : TierPriceRepository {

    override fun findAllFor(skus: Collection<SkuCode>): List<TierPrice> {
        if (skus.isEmpty()) return emptyList()
        return jpa.findBySkuIn(skus.map { it.value }).map { it.toDomain() }
    }

    @Transactional
    override fun replaceFor(sku: SkuCode, rows: List<TierPrice>) {
        require(rows.all { it.sku == sku }) { "All rows must belong to $sku" }
        jpa.deleteBySku(sku.value)
        // Force the delete out before the inserts. Without this both sit in the same
        // flush and Hibernate may order the insert first, breaching
        // (sku, tier_id, min_qty) when a row is being replaced with the same key.
        jpa.flush()
        jpa.saveAll(
            rows.map {
                TierPriceDO(
                    sku = it.sku.value,
                    tierId = it.tierId.value,
                    price = it.price.amount,
                    minQty = it.minQty.value,
                )
            }
        )
    }

    private fun TierPriceDO.toDomain() = TierPrice(
        sku = SkuCode(sku),
        tierId = TierId(tierId),
        price = Money.of(price),
        minQty = Quantity(minQty),
    )
}
