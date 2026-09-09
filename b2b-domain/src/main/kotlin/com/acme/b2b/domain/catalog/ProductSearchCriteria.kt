package com.acme.b2b.domain.catalog

import com.acme.b2b.types.Money

/**
 * What a dealer is filtering the catalog by. A domain value rather than a bag of
 * request parameters, so the repository port does not take web-shaped arguments.
 */
data class ProductSearchCriteria(
    val text: String? = null,
    val categoryId: Long? = null,
    /**
     * Bounds apply to the dealer's own resolved price, not list price — filtering on
     * numbers the dealer cannot see is filtering on nothing they recognise.
     */
    val priceMin: Money? = null,
    val priceMax: Money? = null,
    val sort: ProductSort = ProductSort.DEFAULT,
    /** Dealers see only visible products; an admin sees hidden ones too. */
    val onlyVisible: Boolean = true,
    /** Narrows to one visibility. Only meaningful when [onlyVisible] is false. */
    val visibility: ProductVisibility? = null,
)

/**
 * Sorting as a field plus a direction rather than one enum constant per combination —
 * four fields times two directions is eight constants that all mean the same two things.
 */
enum class ProductSortField { SPU_CODE, NAME, BRAND, PRICE }

enum class SortDirection { ASC, DESC }

data class ProductSort(
    val field: ProductSortField = ProductSortField.SPU_CODE,
    val direction: SortDirection = SortDirection.ASC,
) {
    companion object {
        /** The catalog's natural order: by code, ascending. */
        val DEFAULT = ProductSort()
    }
}
