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

    override fun isAssignedToProducts(categoryId: Long): Boolean =
        productCategories.existsByCategoryId(categoryId)

    override fun existsBySlug(slug: String): Boolean = jpa.existsBySlug(slug)

    override fun productCountsByCategory(): Map<Long, Long> =
        productCategories.countsByCategory().associate { (categoryId, count) ->
            categoryId as Long to (count as Number).toLong()
        }

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

    override fun deleteById(id: Long) = jpa.deleteById(id)
}
