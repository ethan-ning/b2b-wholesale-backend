package com.acme.b2b.domain.sellfox

/**
 * Turns a flat list of Sellfox commodities into SPU families.
 *
 * Sellfox has an `spu` field and leaves it null on essentially every row, so the portal
 * derives the grouping. It does not do that by parsing SKU strings. Sellfox already
 * states the relationship: a pack SKU declares the single-unit SKU it contains, and how
 * many of it.
 *
 *     WM7C310J255-QT4-1     children: []                        base
 *     WM7C310J255-QT4-2     children: [WM7C310J255-QT4-1 × 2]   pack
 *     WM7C310J255-QT4-4     children: [WM7C310J255-QT4-1 × 4]   pack
 *
 * which is exactly the portal's model: one SPU whose SKUs vary by pack quantity.
 *
 * Reading the declared structure rather than the SKU text is what keeps genuinely
 * separate products apart. "AX-K210-ZN-4" and "AX-K210-ZN-4 S" differ by one trailing
 * token and are different products; any strip-the-suffix rule merges them, while their
 * child lists never confuse the two.
 *
 * Pure: no repository, no clock, no framework.
 */
object SpuGrouping {

    /** What the SKUs of a family vary along. */
    enum class Axis { PACK_QUANTITY, SIZE }

    /** A family that becomes one Product: an SPU code, and the SKUs beneath it. */
    data class Family(
        val spuCode: String,
        val name: String,
        val fullCid: String,
        val members: List<Member>,
        /** Null for a family of one — a lone SKU varies along nothing. */
        val axis: Axis? = null,
    )

    data class Member(
        val commodity: SellfoxCommodity,
        val packQuantity: Int,
        /** True for the single-unit SKU the packs are built from. */
        val isBase: Boolean,
        /** The value on the family's axis: "6" for a pack, "XL" for a size. */
        val variantValue: String? = null,
    )

    /**
     * Groups the given commodities. Only those passing [inScope] become families, but the
     * whole list informs the result: a pack in scope may name a base that was filtered
     * out, and an SPU code must not collide with some other commodity's SKU.
     */
    fun group(all: List<SellfoxCommodity>, inScope: (SellfoxCommodity) -> Boolean): List<Family> {
        val bySku = all.associateBy { it.sku }
        val allSkus = bySku.keys
        val wanted = all.filter { it.isActive && inScope(it) }

        val packFamilies = wanted
            .groupBy { packedSku(it) ?: it.sku }
            .map { (baseSku, group) -> draftFamily(baseSku, group, bySku, allSkus) }

        // Sizes are grouped only among what the pack pass left alone. A pack relationship
        // is declared by the supplier and a size relationship is inferred from the code,
        // so where they disagree the declared one wins — and one SPU has one axis, so
        // they cannot both apply.
        val (singles, multi) = packFamilies.partition { it.members.size == 1 }
        val sizeGrouped = groupBySize(singles, allSkus)

        return resolveCollisions(multi + sizeGrouped).sortedBy { it.spuCode }
    }

    /**
     * Folds size runs into one family: KTG-08-S, -M, -L, -XL become KTG-08 on a Size axis.
     *
     * Nothing in Sellfox says these are related — every one is a plain SKU with no
     * children — so unlike packs this is a guess, and it is deliberately a timid one.
     * The trailing token must be a size from a closed list, and at least two SKUs must
     * share a stem with different sizes. A lone SKU ending in "-S" stays a product of its
     * own, which is what keeps "RB-05 SCREW-FT" and its neighbours out of this.
     */
    private fun groupBySize(singles: List<Family>, allSkus: Set<String>): List<Family> {
        val (sized, plain) = singles.partition { sizeTokenOf(it.spuCode) != null }

        val families = sized
            .groupBy { it.spuCode.dropLast(sizeTokenOf(it.spuCode)!!.length + 1) }
            .flatMap { (stem, group) ->
                val sizes = group.mapNotNull { sizeTokenOf(it.spuCode) }
                val groupable = group.size > 1 &&
                    sizes.distinct().size == group.size &&
                    stem.isNotBlank() &&
                    stem !in allSkus

                if (!groupable) return@flatMap group

                val members = group
                    .map { family ->
                        val member = family.members.single()
                        member.copy(variantValue = sizeTokenOf(family.spuCode))
                    }
                    .sortedBy { SIZE_ORDER.indexOf(it.variantValue) }

                listOf(
                    Family(
                        spuCode = stem,
                        name = group.first().name,
                        fullCid = group.first().fullCid,
                        members = members,
                        axis = Axis.SIZE,
                    )
                )
            }

        return families + plain
    }

    /** The trailing size token, or null when the code does not end in one. */
    private fun sizeTokenOf(code: String): String? =
        code.substringAfterLast('-', "").takeIf { it.isNotBlank() && it in SIZE_ORDER }

    private fun draftFamily(
        baseSku: String,
        group: List<SellfoxCommodity>,
        bySku: Map<String, SellfoxCommodity>,
        allSkus: Set<String>,
    ): Family {
        val members = group
            .map { commodity ->
                val packed = packedSku(commodity)
                Member(
                    commodity = commodity,
                    packQuantity = if (packed == null) 1 else commodity.children.single().quantity,
                    isBase = packed == null,
                )
            }
            .sortedBy { it.packQuantity }

        // Name and category come from the base where we have it: a pack carries the same
        // product's name, and its category is sometimes the packaging one instead.
        val describedBy = bySku[baseSku] ?: members.first().commodity

        return Family(
            spuCode = spuCodeFor(members.map { it.commodity.sku }, baseSku, allSkus),
            name = describedBy.name.ifBlank { baseSku },
            fullCid = describedBy.fullCid,
            members = members.map { it.copy(variantValue = "${it.packQuantity}") },
            axis = if (members.size > 1) Axis.PACK_QUANTITY else null,
        )
    }

    /**
     * The single-unit SKU this commodity packs, or null when it is not a pack.
     *
     * Exactly one child means a pack. More than one is a kit — a swing set of two
     * different parts is its own product, not a bulk variant of either — so a kit heads
     * its own family. Sellfox's `isGroup` flag is not consulted: it marks both, and the
     * child list is what actually distinguishes them.
     */
    private fun packedSku(commodity: SellfoxCommodity): String? =
        commodity.children.singleOrNull()?.takeIf { it.quantity > 0 }?.sku

    /**
     * The SPU code for a family: the longest prefix its SKUs share, trimmed back to a
     * token boundary.
     *
     *     WM7C310J255-QT4-1 / -2 / -4     ->  WM7C310J255-QT4
     *     AX-K318-12 / AX-K318-24         ->  AX-K318
     *     RB-QF01-S / RB-QF01-S-2P        ->  RB-QF01-S   (the base names the family)
     *
     * A common prefix rather than a suffix rule because a pack's quantity is not always
     * appended to the base — "AX-K318-24" packs two of "AX-K318-12", and no amount of
     * stripping turns one into the other.
     *
     * Falls back to the base SKU when the prefix would be blank, would not actually
     * contain every member, or would collide with a different commodity's SKU — that last
     * one matters, because a family reaching for "PL-X" while a separate product is
     * literally called "PL-X" would put two different things under one code.
     */
    private fun spuCodeFor(skus: List<String>, baseSku: String, allSkus: Set<String>): String {
        val candidate = commonPrefix(skus).trimEnd('-', ' ')

        val usable = candidate.isNotBlank() &&
            skus.all { it == candidate || it.startsWith("$candidate-") } &&
            (candidate !in allSkus || candidate in skus)

        return if (usable) candidate else baseSku
    }

    private fun commonPrefix(values: List<String>): String =
        values.reduce { acc, value -> acc.commonPrefixWith(value) }

    /**
     * Two families can still land on the same code. Rather than pick a winner, each is
     * broken into one product per SKU, named after that SKU — a code is unique that way
     * by construction, and no SKU is filed under a product it does not belong to.
     *
     * The pack relationship is what gets lost, so this stays a last resort: it does not
     * fire on any category in the current catalog, and a run that hits it says so.
     */
    private fun resolveCollisions(families: List<Family>): List<Family> {
        val contested = families
            .groupBy { it.spuCode }
            .filterValues { it.size > 1 }
            .keys

        if (contested.isEmpty()) return families

        return families.flatMap { family ->
            if (family.spuCode !in contested) listOf(family)
            else family.members.map { member ->
                family.copy(
                    spuCode = member.commodity.sku,
                    name = member.commodity.name.ifBlank { member.commodity.sku },
                    members = listOf(member.copy(variantValue = null)),
                    axis = null,
                )
            }
        }
    }

    /**
     * The sizes a trailing token may be, smallest first — the list doubles as the display
     * order, since sizes do not sort lexically ("L" before "M" before "S" is nonsense).
     *
     * Closed on purpose. Anything not here is treated as part of the product code, which
     * is the safe direction to be wrong in: a missed family is a catalog an admin can
     * fix by hand, while a wrong one silently files two products as variants of each other.
     */
    private val SIZE_ORDER = listOf(
        "XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL",
        "2XL", "3XL", "4XL", "5XL", "6XL",
    )
}
