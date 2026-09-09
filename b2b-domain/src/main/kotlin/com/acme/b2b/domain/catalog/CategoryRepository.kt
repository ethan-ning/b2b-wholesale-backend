package com.acme.b2b.domain.catalog

/** A node of the merchandising taxonomy. Ours, not Sellfox's. */
data class Category(
    val id: Long?,
    val name: String,
    val slug: String,
    val parentId: Long?,
    val sortOrder: Int,
    val children: List<Category> = emptyList(),
)

interface CategoryRepository {
    fun findTree(): List<Category>
    fun findDescendantIds(categoryId: Long): List<Long>
    fun save(category: Category): Category
    fun deleteById(id: Long)
}
