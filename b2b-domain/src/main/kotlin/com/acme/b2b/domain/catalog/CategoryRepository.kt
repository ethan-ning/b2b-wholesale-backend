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
}

interface CategoryRepository {
    fun findById(id: Long): Category?
    fun findTree(): List<Category>
    fun findDescendantIds(categoryId: Long): List<Long>
    fun save(category: Category): Category
    fun deleteById(id: Long)

    fun hasChildren(categoryId: Long): Boolean
    /** True when any product is filed under it — deleting would orphan them. */
    fun isAssignedToProducts(categoryId: Long): Boolean
    fun existsBySlug(slug: String): Boolean
}
