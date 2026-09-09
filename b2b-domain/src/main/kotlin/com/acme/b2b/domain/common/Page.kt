package com.acme.b2b.domain.common

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
    val totalPages: Int
        get() = if (page.size == 0) 0 else ((totalElements + page.size - 1) / page.size).toInt()

    fun <R> map(transform: (T) -> R): PageOf<R> = PageOf(content.map(transform), totalElements, page)

    companion object {
        /** Applies an in-memory window. For adapters that cannot page in the query. */
        fun <T> of(all: List<T>, page: Page): PageOf<T> =
            PageOf(all.drop(page.offset).take(page.size), all.size.toLong(), page)
    }
}
