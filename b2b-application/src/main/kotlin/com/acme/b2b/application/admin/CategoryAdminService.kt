package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.CategoryNodeDTO
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.Category
import com.acme.b2b.domain.catalog.CategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer

/**
 * The merchandising taxonomy. Ours entirely — the ERP's own categories are not used,
 * because what a warehouse system files a part under is rarely how a dealer looks for it.
 */
@Service
@Transactional(readOnly = true)
class CategoryAdminService(
    private val categories: CategoryRepository,
) {

    fun tree(): List<CategoryNodeDTO> = categories.findTree().map { it.toDto() }

    @Transactional
    fun create(command: CreateCategoryCommand): CategoryNodeDTO {
        val name = command.name.trim()
        if (name.isBlank()) throw UseCaseViolation("Category name must not be blank")

        command.parentId?.let {
            categories.findById(it) ?: throw UseCaseViolation("No such parent category: $it")
        }

        val slug = uniqueSlug(name)
        val saved = categories.save(
            Category(id = null, name = name, slug = slug, parentId = command.parentId, sortOrder = 0)
        )
        return saved.toDto()
    }

    @Transactional
    fun rename(id: Long, command: RenameCategoryCommand): CategoryNodeDTO {
        val existing = categories.findById(id)
            ?: throw NoSuchElementException("No category with id $id")

        val name = command.name.trim()
        if (name.isBlank()) throw UseCaseViolation("Category name must not be blank")

        // The slug is left alone: it may already be in a URL a dealer has bookmarked.
        return categories.save(existing.copy(name = name)).toDto()
    }

    /**
     * Refuses rather than cascades. Deleting a branch would silently unfile every
     * product beneath it, and the admin cannot see that from the button they pressed.
     */
    @Transactional
    fun delete(id: Long) {
        categories.findById(id) ?: throw NoSuchElementException("No category with id $id")

        if (categories.hasChildren(id)) {
            throw UseCaseViolation("Category $id has sub-categories; remove or move them first")
        }
        if (categories.isAssignedToProducts(id)) {
            throw UseCaseViolation("Category $id still has products filed under it")
        }
        categories.deleteById(id)
    }

    /** "Auto Parts" -> "auto-parts", with a numeric suffix if that is taken. */
    private fun uniqueSlug(name: String): String {
        val base = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "category" }

        if (!categories.existsBySlug(base)) return base
        return generateSequence(2) { it + 1 }
            .map { "$base-$it" }
            .first { !categories.existsBySlug(it) }
    }

    private fun Category.toDto(): CategoryNodeDTO = CategoryNodeDTO(
        id = id,
        name = name,
        slug = slug,
        parentId = parentId,
        sortOrder = sortOrder,
        children = children.map { it.toDto() },
    )
}
