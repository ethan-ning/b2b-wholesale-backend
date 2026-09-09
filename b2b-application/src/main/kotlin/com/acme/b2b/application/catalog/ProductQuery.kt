package com.acme.b2b.application.catalog

import java.math.BigDecimal

/**
 * A read use case's input, in the application layer's own vocabulary. The web layer
 * translates request parameters into this; the application translates it into the
 * domain's ProductSearchCriteria. Neither knows about the other's types.
 */
data class ProductQuery(
    val search: String? = null,
    val categoryId: Long? = null,
    val priceMin: BigDecimal? = null,
    val priceMax: BigDecimal? = null,
    val sort: String = "relevance",
    val page: Int = 0,
    val size: Int = 10,
)
