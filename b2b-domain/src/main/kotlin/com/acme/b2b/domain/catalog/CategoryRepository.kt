package com.acme.b2b.domain.catalog

/** A node of the merchandising taxonomy. Ours, not the ERP's. */
data class Category(
    val id: Long?,
    val name: String,
    val slug: String,
    val parentId: Long?,
    val sortOrder: Int,
    val children: List<Category> = emptyList(),
) {
    init { require(name.isNotBlank()) { "Category name must not be blank" } }

    companion object {
        /**
         * Root, sub, sub-sub. A fourth level buys precision nobody browses to — dealers
         * stop drilling well before it, and every extra level is one more place a product
         * can be filed where it will not be found.
         */
        const val MAX_DEPTH = 3
    }
}

interface CategoryRepository {
    fun findById(id: Long): Category?
    fun findTree(): List<Category>
    fun findDescendantIds(categoryId: Long): List<Long>
    fun save(category: Category): Category
    fun deleteById(id: Long)

    fun hasChildren(categoryId: Long): Boolean

    /** 1 for a root. Used to hold the tree to [Category.MAX_DEPTH]. */
    fun depthOf(categoryId: Long): Int

    /**
     * Which products are filed under each category, for the whole tree in one query.
     * Ids rather than counts because a parent's total is the *distinct* products across
     * its subtree: a product filed under both "Apparel" and "Apparel > Gloves" is one
     * product, and summing counts would report it twice.
     */
    fun productIdsByCategory(): Map<Long, Set<Long>>
    fun existsBySlug(slug: String): Boolean
}
