package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.CategoryNodeDTO
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.catalog.Category
import com.acme.b2b.domain.catalog.CategoryRepository
import com.acme.b2b.domain.catalog.ProductRepository
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
    private val products: ProductRepository,
) {

    /**
     * The tree, annotated with what an admin needs before acting: what is filed in each
     * node and beneath it, how deep it sits, and whether it can take a child. All of it
     * from one products query for the whole tree, not one per node.
     */
    fun tree(): List<CategoryNodeDTO> {
        val productIds = categories.productIdsByCategory()
        return categories.findTree().map { it.toDto(productIds, depth = 1).first }
    }

    @Transactional
    fun create(command: CreateCategoryCommand): CategoryNodeDTO {
        val name = command.name.trim()
        if (name.isBlank()) throw UseCaseViolation("Category name must not be blank")

        command.parentId?.let { parentId ->
            categories.findById(parentId)
                ?: throw UseCaseViolation("No such parent category: $parentId")
            val parentDepth = categories.depthOf(parentId)
            if (parentDepth >= Category.MAX_DEPTH) {
                throw UseCaseViolation(
                    "Categories go ${Category.MAX_DEPTH} levels deep at most; " +
                        "this parent is already at level $parentDepth"
                )
            }
        }

        val slug = uniqueSlug(name)
        val saved = categories.save(
            Category(id = null, name = name, slug = slug, parentId = command.parentId, sortOrder = 0)
        )
        return nodeOf(requireNotNull(saved.id))
    }

    @Transactional
    fun rename(id: Long, command: RenameCategoryCommand): CategoryNodeDTO {
        val existing = categories.findById(id)
            ?: throw NoSuchElementException("No category with id $id")

        val name = command.name.trim()
        if (name.isBlank()) throw UseCaseViolation("Category name must not be blank")

        // The slug is left alone: it may already be in a URL a dealer has bookmarked.
        categories.save(existing.copy(name = name))
        return nodeOf(id)
    }

    /**
     * Deletes a leaf, unfiling whatever was in it. Products are not deleted — losing a
     * category is losing a shelf, not the stock on it — so each one is asked to drop the
     * link, which also hands the primary slot to another of its categories if this was it.
     *
     * Sub-categories still block: removing a branch would take nodes with it that the
     * admin never saw, and the count on the button does not tell them which.
     */
    @Transactional
    fun delete(id: Long) {
        categories.findById(id) ?: throw NoSuchElementException("No category with id $id")

        if (categories.hasChildren(id)) {
            throw UseCaseViolation("Category $id has sub-categories; remove or move them first")
        }

        products.findByCategoryId(id).forEach { products.save(it.withoutCategory(id)) }
        categories.deleteById(id)
    }

    /**
     * Reads one node back out of the freshly built tree. A node's depth, counts and
     * deletability are all facts about its position, and the row a save returns does not
     * know its own position.
     */
    private fun nodeOf(id: Long): CategoryNodeDTO {
        fun find(nodes: List<CategoryNodeDTO>): CategoryNodeDTO? =
            nodes.firstOrNull { it.id == id } ?: nodes.firstNotNullOfOrNull { find(it.children) }
        return find(tree()) ?: throw NoSuchElementException("No category with id $id")
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

    /**
     * Returns the node alongside the distinct products in its subtree — the caller needs
     * the set to roll up into its own, but it is working state, not something to put on
     * the wire.
     */
    private fun Category.toDto(
        productIds: Map<Long, Set<Long>>,
        depth: Int,
    ): Pair<CategoryNodeDTO, Set<Long>> {
        val children = children.map { it.toDto(productIds, depth + 1) }
        val direct = id?.let { productIds[it] }.orEmpty()

        // Union, not sum: a product filed under both a parent and its child is one
        // product, and an admin reading "12 total" means twelve things.
        val subtree = direct + children.flatMap { (_, ids) -> ids }

        // Mirrors delete()'s only remaining rule, so the button's state and the API agree.
        val blockedReason = if (children.isEmpty()) null else
            "Has ${children.size} sub-categor${if (children.size == 1) "y" else "ies"}"

        val dto = CategoryNodeDTO(
            id = id,
            name = name,
            slug = slug,
            parentId = parentId,
            sortOrder = sortOrder,
            depth = depth,
            productCount = direct.size.toLong(),
            totalProductCount = subtree.size.toLong(),
            canAddChild = depth < Category.MAX_DEPTH,
            deletable = blockedReason == null,
            blockedReason = blockedReason,
            children = children.map { (child, _) -> child },
        )
        return dto to subtree
    }
}
