package com.acme.b2b.infrastructure.sellfox

import com.acme.b2b.domain.sellfox.SellfoxCatalogPort
import com.acme.b2b.domain.sellfox.SellfoxChild
import com.acme.b2b.domain.sellfox.SellfoxCommodity
import com.acme.b2b.domain.sellfox.SellfoxInventoryPort
import com.acme.b2b.domain.sellfox.SellfoxStock
import com.acme.b2b.domain.sellfox.SellfoxWarehouse
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component

/**
 * Sellfox's wire shape, translated to the domain's. Every field the portal does not use
 * stops here — Sellfox returns around sixty per commodity, most of them customs
 * paperwork, and each one carried further is one more thing that could be depended on.
 */
@Component
class SellfoxCatalogAdapter(private val client: SellfoxApiClient) : SellfoxCatalogPort {

    override fun listCommodities(): List<SellfoxCommodity> =
        client.pageThrough(
            path = COMMODITY_PATH,
            body = { emptyMap() },
            extract = { data -> data.path("rows").map { it.toCommodity() } },
        )

    private fun JsonNode.toCommodity() = SellfoxCommodity(
        commodityId = path("id").asText(),
        sku = path("sku").asText(),
        name = path("name").asText(""),
        fullCid = path("fullCid").asText(""),
        fullName = path("fullName").asText(""),
        declaredSpu = path("spu").asText("").takeIf { it.isNotBlank() },
        weightGrams = path("weight").asText("").toDoubleOrNull(),
        children = path("childSkus").mapNotNull { child ->
            val sku = child.path("sku").asText("")
            val quantity = child.path("num").asText("").toIntOrNull() ?: 0
            if (sku.isBlank()) null else SellfoxChild(sku, quantity)
        },
        state = path("state").asText(""),
    )

    private companion object {
        const val COMMODITY_PATH = "/api/commodity/pageList.json"
    }
}

@Component
class SellfoxInventoryAdapter(private val client: SellfoxApiClient) : SellfoxInventoryPort {

    override fun listWarehouses(): List<SellfoxWarehouse> =
        client.pageThrough(
            path = WAREHOUSE_PATH,
            pageSize = 50,
            body = { emptyMap() },
            extract = { data ->
                data.path("rows").mapNotNull { row ->
                    row.path("id").asText("").toLongOrNull()?.let { id ->
                        SellfoxWarehouse(
                            warehouseId = id,
                            name = row.path("name").asText(""),
                            type = row.path("type").asText("").toIntOrNull(),
                        )
                    }
                }
            },
        )

    override fun listStock(warehouseId: Long): List<SellfoxStock> =
        client.pageThrough(
            path = STOCK_PATH,
            body = { mapOf("warehouseId" to "$warehouseId") },
            extract = { data ->
                data.path("rows").mapNotNull { row ->
                    val sku = row.path("commoditySku").asText("")
                    if (sku.isBlank()) null else SellfoxStock(
                        sku = sku,
                        warehouseId = warehouseId,
                        available = row.path("stockAvailable").asInt(0),
                        // stockWait is in-transit units. Not to be confused with
                        // onWayPurchase, which is a currency amount.
                        incoming = row.path("stockWait").asInt(0),
                    )
                }
            },
        )

    private companion object {
        const val WAREHOUSE_PATH = "/api/warehouseManage/warehouseList.json"
        const val STOCK_PATH = "/api/warehouseManage/warehouseItemList.json"
    }
}
