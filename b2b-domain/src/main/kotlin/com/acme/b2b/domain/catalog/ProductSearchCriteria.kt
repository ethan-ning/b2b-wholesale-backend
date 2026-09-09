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
    /** Dealers see only ACTIVE products; an admin browses drafts and archived ones too. */
    val onlyPublished: Boolean = true,
    /** Narrows to one status. Only meaningful when [onlyPublished] is false. */
    val status: ProductStatus? = null,
)

enum class ProductSort { RELEVANCE, PRICE_ASC, PRICE_DESC, NAME_ASC }
