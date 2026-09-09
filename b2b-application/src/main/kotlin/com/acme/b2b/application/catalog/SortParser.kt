package com.acme.b2b.application.catalog

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.ProductSort
import com.acme.b2b.domain.catalog.ProductSortField
import com.acme.b2b.domain.catalog.SortDirection

/**
 * Turns the wire's sort parameters into the domain's. Shared by the dealer and admin
 * endpoints so "price" cannot come to mean different things on the two screens.
 *
 * An unknown field is rejected rather than silently ignored: a typo that quietly falls
 * back to the default looks like the sort control is broken.
 */
object SortParser {

    private val FIELDS = mapOf(
        "spucode" to ProductSortField.SPU_CODE,
        "spu_code" to ProductSortField.SPU_CODE,
        "code" to ProductSortField.SPU_CODE,
        "name" to ProductSortField.NAME,
        "brand" to ProductSortField.BRAND,
        "price" to ProductSortField.PRICE,
        "basewholesaleprice" to ProductSortField.PRICE,
    )

    fun parse(field: String?, direction: String?): ProductSort {
        if (field.isNullOrBlank()) return ProductSort.DEFAULT

        // The dealer portal sends a single combined token: price_asc, name_asc, relevance.
        legacy(field)?.let { return it }

        val parsedField = FIELDS[field.lowercase()]
            ?: throw UseCaseViolation("Cannot sort by '$field'. Try one of: ${FIELDS.keys.sorted()}")

        return ProductSort(parsedField, parseDirection(direction))
    }

    private fun legacy(token: String): ProductSort? = when (token.lowercase()) {
        "relevance" -> ProductSort(ProductSortField.NAME, SortDirection.ASC)
        "price_asc" -> ProductSort(ProductSortField.PRICE, SortDirection.ASC)
        "price_desc" -> ProductSort(ProductSortField.PRICE, SortDirection.DESC)
        "name_asc" -> ProductSort(ProductSortField.NAME, SortDirection.ASC)
        else -> null
    }

    private fun parseDirection(raw: String?): SortDirection = when (raw?.lowercase()) {
        null, "", "asc", "ascend", "ascending" -> SortDirection.ASC
        "desc", "descend", "descending" -> SortDirection.DESC
        else -> throw UseCaseViolation("Unknown sort direction: $raw")
    }
}
