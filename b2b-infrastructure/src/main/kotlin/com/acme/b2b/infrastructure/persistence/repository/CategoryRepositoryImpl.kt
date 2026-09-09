package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.catalog.Category
import com.acme.b2b.domain.catalog.CategoryRepository
import com.acme.b2b.infrastructure.persistence.entity.CategoryDO
import com.acme.b2b.infrastructure.persistence.jpa.CategoryJpaRepository
import com.acme.b2b.infrastructure.persistence.jpa.ProductCategoryJpaRepository
import org.springframework.stereotype.Repository

@Repository
class CategoryRepositoryImpl(
    private val jpa: CategoryJpaRepository,
    private val productCategories: ProductCategoryJpaRepository,
) : CategoryRepository {

    override fun findById(id: Long): Category? =
        jpa.findById(id).orElse(null)?.let {
            Category(it.id, it.name, it.slug, it.parentId, it.sortOrder)
        }

    override fun hasChildren(categoryId: Long): Boolean = jpa.existsByParentId(categoryId)

    override fun existsBySlug(slug: String): Boolean = jpa.existsBySlug(slug)

    override fun depthOf(categoryId: Long): Int {
        val parentOf = jpa.findAll().associate { it.id to it.parentId }
        var depth = 1
        var cursor = parentOf[categoryId]
        // Bounded by the node count: a cycle would otherwise spin here forever.
        while (cursor != null && depth <= parentOf.size) {
            depth++
            cursor = parentOf[cursor]
        }
        return depth
    }

    override fun productIdsByCategory(): Map<Long, Set<Long>> =
        productCategories.categoryProductPairs()
            .groupBy({ it[0] as Long }, { it[1] as Long })
            .mapValues { (_, ids) -> ids.toSet() }

    override fun findTree(): List<Category> {
        val all = jpa.findAll()
        val byParent = all.groupBy { it.parentId }
        fun build(parentId: Long?): List<Category> =
            byParent[parentId].orEmpty().sortedBy { it.sortOrder }.map { row ->
                Category(
                    id = row.id,
                    name = row.name,
                    slug = row.slug,
                    parentId = row.parentId,
                    sortOrder = row.sortOrder,
                    children = build(row.id),
                )
            }
        return build(null)
    }

    override fun findDescendantIds(categoryId: Long): List<Long> {
        val byParent = jpa.findAll().groupBy { it.parentId }
        val collected = mutableListOf<Long>()
        fun walk(id: Long) {
            byParent[id].orEmpty().forEach { child ->
                child.id?.let { collected.add(it); walk(it) }
            }
        }
        walk(categoryId)
        return collected
    }

    override fun save(category: Category): Category {
        val row = category.id?.let { jpa.findById(it).orElse(null) } ?: CategoryDO()
        row.name = category.name
        row.slug = category.slug
        row.parentId = category.parentId
        row.sortOrder = category.sortOrder
        val saved = jpa.save(row)
        return category.copy(id = saved.id)
    }

    /**
     * Flushes first. The caller unfiles this category's products immediately before
     * calling here, and those orphan deletes are still sitting in the persistence
     * context — leaving the FK from product_category to be violated by a category
     * delete that Hibernate is free to write out first.
     */
    override fun deleteById(id: Long) {
        jpa.flush()
        jpa.deleteById(id)
    }
}
