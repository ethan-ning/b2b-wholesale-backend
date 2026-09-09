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
    val sort: ProductSort = ProductSort.RELEVANCE,
    val onlyPublished: Boolean = true,
)

enum class ProductSort { RELEVANCE, PRICE_ASC, PRICE_DESC, NAME_ASC }

data class Page(val number: Int, val size: Int) {
    init {
        require(number >= 0) { "Page number must not be negative" }
        require(size in 1..MAX_SIZE) { "Page size must be between 1 and $MAX_SIZE" }
    }
    val offset: Int get() = number * size

    companion object {
        const val MAX_SIZE = 200
        val DEFAULT = Page(0, 10)
    }
}

data class PageOf<T>(
    val content: List<T>,
    val totalElements: Long,
    val page: Page,
) {
    val totalPages: Int get() = if (page.size == 0) 0 else ((totalElements + page.size - 1) / page.size).toInt()

    fun <R> map(transform: (T) -> R): PageOf<R> = PageOf(content.map(transform), totalElements, page)
}
