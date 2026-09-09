package com.acme.b2b.domain.sellfox

/**
 * Turns a flat list of Sellfox commodities into SPU families.
 *
 * Sellfox is inconsistent about saying how its SKUs relate — the same catalog declares
 * `NDR24-ORANGE-6` as six of `NDR24-ORANGE-1` and leaves the whole `RB-VLM4-*` ladder
 * saying nothing at all. So this reads every signal it has, most trustworthy first, and
 * only guesses where nothing was declared:
 *
 *   1. A declared SPU        — Sellfox's own `spu` field. Rare but authoritative.
 *   2. A declared pack       — a SKU naming the single-unit SKU it contains, and how many.
 *   3. An inferred ladder    — same stem, different trailing pack counts.
 *   4. An inferred size run  — same stem, different trailing sizes.
 *   5. A lone pack count     — one SKU whose code ends in the quantity it holds.
 *
 * The order is the point: a declared relationship is a fact and an inferred one is a
 * guess, so nothing inferred may override something stated.
 */
object SpuGrouping {

    /** What the SKUs of a family vary along. */
    enum class Axis { PACK_QUANTITY, SIZE }

    /** Which of the five rules named the family. Carried so a run can report what it relied on. */
    enum class Basis { DECLARED_SPU, DECLARED_PACK, INFERRED_PACK, INFERRED_SIZE, SINGLE }

    /** A family that becomes one Product: an SPU code, and the SKUs beneath it. */
    data class Family(
        val spuCode: String,
        val name: String,
        val fullCid: String,
        val members: List<Member>,
        /** Null for a family of one — a lone SKU varies along nothing. */
        val axis: Axis? = null,
        val basis: Basis = Basis.SINGLE,
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

        // 1. Sellfox said so outright.
        val (declared, undeclared) = wanted.partition { declaredSpuOf(it) != null }
        val declaredFamilies = declared
            .groupBy { declaredSpuOf(it)!! }
            .map { (spu, group) -> declaredFamily(spu, group) }

        // 2. A pack naming what it contains. Anything already claimed above is left out,
        //    so a declared SPU cannot be split by a pack relationship pointing elsewhere.
        val packFamilies = undeclared
            .groupBy { packedSku(it) ?: it.sku }
            .map { (baseSku, group) -> draftFamily(baseSku, group, bySku, allSkus) }

        // 3-5. Only what the declarations left as singletons is guessed at.
        val (singles, grouped) = packFamilies.partition { it.members.size == 1 }
        val ladders = groupByPackLadder(singles, allSkus)
        val (stillSingle, laddered) = ladders.partition { it.members.size == 1 }
        val sized = groupBySize(stillSingle, allSkus)
        val settled = sized.map { if (it.members.size == 1) withLonePackCount(it, allSkus) else it }

        return resolveCollisions(declaredFamilies + grouped + laddered + settled).sortedBy { it.spuCode }
    }

    // ─── 1. Declared SPU ─────────────────────────────────────────────────

    /**
     * Sellfox's own SPU for this commodity, or null when it left the field empty — which
     * it does on all but a fraction of a percent of rows.
     *
     * Stripped of anything a product code would not carry. The field is free text beside
     * a `spuName` that holds Chinese, and one stray character would make an SPU code the
     * catalog refuses, dropping the product entirely.
     */
    private fun declaredSpuOf(commodity: SellfoxCommodity): String? =
        commodity.declaredSpu
            ?.filter { it.isLetterOrDigit() && it.code < 128 || it in CODE_PUNCTUATION }
            ?.trim(*CODE_PUNCTUATION)
            ?.takeIf { it.isNotBlank() }

    private fun declaredFamily(spu: String, group: List<SellfoxCommodity>): Family {
        val members = group
            .map { commodity ->
                val pack = packSuffixOf(commodity.sku)
                Member(
                    commodity = commodity,
                    packQuantity = pack?.quantity ?: 1,
                    // Nothing here is a pack of anything else; they are siblings under a
                    // name Sellfox gave them.
                    isBase = pack == null || pack.quantity == 1,
                    variantValue = pack?.quantity?.toString(),
                )
            }
            .sortedBy { it.packQuantity }

        return Family(
            spuCode = spu,
            name = group.first().name.ifBlank { spu },
            fullCid = group.first().fullCid,
            members = if (members.size == 1) members.map { it.copy(variantValue = null) } else members,
            axis = if (members.size > 1) Axis.PACK_QUANTITY else null,
            basis = Basis.DECLARED_SPU,
        )
    }

    // ─── 2. Declared pack ────────────────────────────────────────────────

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
            members = members.map {
                it.copy(variantValue = if (members.size > 1) "${it.packQuantity}" else null)
            },
            axis = if (members.size > 1) Axis.PACK_QUANTITY else null,
            basis = if (members.size > 1) Basis.DECLARED_PACK else Basis.SINGLE,
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

    // ─── 3. Inferred pack ladder ─────────────────────────────────────────

    /**
     * Folds `RB-VLM4-1/-2/-4/-8/-12/-16` into one product on a Pack Qty axis.
     *
     * Guarded, since nothing declared these: at least two SKUs sharing a stem, all with
     * different counts, and the stem must not itself be a product.
     */
    private fun groupByPackLadder(singles: List<Family>, allSkus: Set<String>): List<Family> {
        val (numbered, plain) = singles.partition { packSuffixOf(it.spuCode) != null }

        val families = numbered
            .groupBy { packSuffixOf(it.spuCode)!!.stem }
            .flatMap { (stem, group) ->
                val quantities = group.map { packSuffixOf(it.spuCode)!!.quantity }
                val groupable = group.size > 1 &&
                    quantities.distinct().size == group.size &&
                    stem.isNotBlank() &&
                    stem !in allSkus

                if (!groupable) return@flatMap group

                val members = group
                    .map { family ->
                        val quantity = packSuffixOf(family.spuCode)!!.quantity
                        family.members.single().copy(
                            packQuantity = quantity,
                            isBase = quantity == 1,
                            variantValue = "$quantity",
                        )
                    }
                    .sortedBy { it.packQuantity }

                listOf(
                    Family(
                        spuCode = stem,
                        name = group.first().name,
                        fullCid = group.first().fullCid,
                        members = members,
                        axis = Axis.PACK_QUANTITY,
                        basis = Basis.INFERRED_PACK,
                    )
                )
            }

        return families + plain
    }

    // ─── 4. Inferred size run ────────────────────────────────────────────

    /**
     * Folds size runs into one family: KTG-08-S, -M, -L, -XL become KTG-08 on a Size axis.
     *
     * Guarded like the ladder above, plus the trailing token must be a size from a closed
     * list. A lone SKU ending in "-S" stays a product of its own, which is what keeps
     * "RB-05 SCREW-FT"-shaped codes out of this.
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
                    .map { it.members.single().copy(variantValue = sizeTokenOf(it.spuCode)) }
                    .sortedBy { SIZE_ORDER.indexOf(it.variantValue) }

                listOf(
                    Family(
                        spuCode = stem,
                        name = group.first().name,
                        fullCid = group.first().fullCid,
                        members = members,
                        axis = Axis.SIZE,
                        basis = Basis.INFERRED_SIZE,
                    )
                )
            }

        return families + plain
    }

    /** The trailing size token, or null when the code does not end in one. */
    private fun sizeTokenOf(code: String): String? =
        code.substringAfterLast('-', "").takeIf { it.isNotBlank() && it in SIZE_ORDER }

    // ─── 5. A lone pack count ────────────────────────────────────────────

    /**
     * `NDR12-YELLOW-10` is a single SKU of a ten-pack, so the product is
     * `NDR12-YELLOW` and the SKU beneath it holds ten.
     *
     * Applied only to what everything above left standing alone, and only when the stem
     * is not itself a product — if `NDR12-YELLOW` exists as its own SKU, the ten-pack
     * would be claiming a name already taken.
     */
    private fun withLonePackCount(family: Family, allSkus: Set<String>): Family {
        val pack = packSuffixOf(family.spuCode) ?: return family
        if (pack.stem.isBlank() || pack.stem in allSkus) return family

        return family.copy(
            spuCode = pack.stem,
            members = family.members.map {
                it.copy(packQuantity = pack.quantity, isBase = pack.quantity == 1, variantValue = null)
            },
        )
    }

    /**
     * A trailing pack count, and what the code says without it.
     *
     *     RB-VLM4-16          -> stem "RB-VLM4",    16
     *     NDR12-YELLOW-10   -> stem "NDR12-YELLOW", 10
     *     AX-K210-ZN-4 S      -> stem "AX-K210-ZN S",   4
     *
     * The third is why the trailing letters are kept in the stem rather than dropped:
     * "AX-K210-ZN-4 S" and "AX-K210-ZN-4" are different products, and a stem that lost
     * the " S" would merge them. A size like "-2XL" does not match, because the letters
     * must be separated by a space.
     */
    private fun packSuffixOf(code: String): PackSuffix? {
        val match = PACK_SUFFIX.matchEntire(code) ?: return null
        val (stem, quantity, trailing) = match.destructured
        return PackSuffix(stem = stem + trailing, quantity = quantity.toIntOrNull() ?: return null)
    }

    private data class PackSuffix(val stem: String, val quantity: Int)

    // ─── SPU codes ───────────────────────────────────────────────────────

    /**
     * The SPU code for a declared-pack family: the longest prefix its SKUs share, trimmed
     * back to a token boundary.
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
     * contain every member, or would collide with a different commodity's SKU.
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
     * The pack relationship is what gets lost, so this stays a last resort.
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
                    basis = Basis.SINGLE,
                )
            }
        }
    }

    /** Digits at the end, optionally followed by a space and letters. */
    private val PACK_SUFFIX = Regex("^(.*)-(\\d+)( [A-Za-z]+)?$")

    /** The rest of a supplier code's alphabet: kept inside a declared SPU, trimmed off its ends. */
    private val CODE_PUNCTUATION = charArrayOf(' ', '-', '.', '+', '_', '/')

    /**
     * The sizes a trailing token may be, smallest first — the list doubles as the display
     * order, since sizes do not sort lexically ("L" before "M" before "S" is nonsense).
     *
     * Closed on purpose. Anything not here is treated as part of the product code, which
     * is the safe direction to be wrong in: a missed family is a catalog an admin can fix
     * by hand, while a wrong one silently files two products as variants of each other.
     */
    private val SIZE_ORDER = listOf(
        "XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL",
        "2XL", "3XL", "4XL", "5XL", "6XL",
    )
}
