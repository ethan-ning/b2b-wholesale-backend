package com.acme.b2b.domain.sellfox

import com.acme.b2b.types.SkuCode
import com.acme.b2b.types.SpuCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cases taken from the live Sellfox catalog, not invented. Where a SKU looks odd it is
 * because the real one is.
 */
class SpuGroupingTest {

    private fun commodity(
        sku: String,
        name: String = "Part",
        cid: String = "100010-100020-100030-",
        children: List<SellfoxChild> = emptyList(),
        state: String = "1",
    ) = SellfoxCommodity(
        commodityId = sku.hashCode().toString(),
        sku = sku,
        name = name,
        fullCid = cid,
        fullName = "供应商甲/重卡配件/轮毂盖",
        weightGrams = null,
        children = children,
        state = state,
    )

    private fun group(vararg commodities: SellfoxCommodity) =
        SpuGrouping.group(commodities.toList()) { true }

    @Test
    fun `packs join the family of the SKU they contain`() {
        val families = group(
            commodity("WM7C310J255-QT4-1"),
            commodity("WM7C310J255-QT4-2", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 2))),
            commodity("WM7C310J255-QT4-4", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 4))),
            commodity("WM7C310J255-QT4-6", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 6))),
        )

        val family = families.single()
        assertEquals("WM7C310J255-QT4", family.spuCode)
        assertEquals(listOf(1, 2, 4, 6), family.members.map { it.packQuantity })
        assertEquals(1, family.members.count { it.isBase })
    }

    @Test
    fun `a colour is a separate product, not a variant`() {
        // Both exist in the live catalog and neither packs the other.
        val families = group(
            commodity("WM7C310J255-QT4-1"),
            commodity("WM7C310J255-QT4-2", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 2))),
            commodity("WM7C310J255-QT4 BK-1"),
            commodity("WM7C310J255-QT4 BK-2", children = listOf(SellfoxChild("WM7C310J255-QT4 BK-1", 2))),
        )

        assertEquals(
            listOf("WM7C310J255-QT4", "WM7C310J255-QT4 BK"),
            families.map { it.spuCode },
        )
    }

    @Test
    fun `a pack whose quantity is not in its code still groups`() {
        // AX-K318-24 is two of AX-K318-12. No suffix rule turns one code into the other;
        // the declared child does.
        val family = group(
            commodity("AX-K318-12"),
            commodity("AX-K318-24", children = listOf(SellfoxChild("AX-K318-12", 2))),
        ).single()

        assertEquals("AX-K318", family.spuCode)
        assertEquals(listOf(1, 2), family.members.map { it.packQuantity })
    }

    @Test
    fun `a base with no numeric suffix names its own family`() {
        val family = group(
            commodity("RB-QF01-S"),
            commodity("RB-QF01-S-2P", children = listOf(SellfoxChild("RB-QF01-S", 2))),
        ).single()

        assertEquals("RB-QF01-S", family.spuCode)
        // The base is the SPU code itself — there is no suffix to give it.
        assertTrue(SkuCode("RB-QF01-S").belongsTo(SpuCode(family.spuCode)))
    }

    @Test
    fun `a kit of different parts is its own product`() {
        // VX-SS01-BLACK+VX-SW110-ORANGE is a swing set, not a bulk pack of either part.
        val families = group(
            commodity("VX-SS01-BLACK"),
            commodity("VX-SW110-ORANGE"),
            commodity(
                "VX-SS01-BLACK-KIT",
                children = listOf(SellfoxChild("VX-SS01-BLACK", 1), SellfoxChild("VX-SW110-ORANGE", 1)),
            ),
        )

        assertEquals(3, families.size)
        assertTrue(families.all { it.members.size == 1 })
    }

    @Test
    fun `a standalone SKU that merely looks like a pack stays alone`() {
        // RB-HV08-4 declares no child, so it is not four of anything.
        val families = group(commodity("RB-HV08-4"), commodity("RB-HV31-4"))

        assertEquals(listOf("RB-HV08-4", "RB-HV31-4"), families.map { it.spuCode })
        assertTrue(families.all { it.members.single().packQuantity == 1 })
    }

    @Test
    fun `every SKU sits beneath the SPU it was given`() {
        val families = group(
            commodity("WM7C310J255-QT4-1"),
            commodity("WM7C310J255-QT4-2", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 2))),
            commodity("AX-K318-12"),
            commodity("AX-K318-24", children = listOf(SellfoxChild("AX-K318-12", 2))),
            commodity("RB-QF01-S"),
            commodity("RB-QF01-S-2P", children = listOf(SellfoxChild("RB-QF01-S", 2))),
            commodity("RB-HV08-4"),
        )

        // The Product aggregate refuses anything else, so this is the invariant that
        // decides whether an import lands or throws halfway through.
        families.forEach { family ->
            val spu = SpuCode(family.spuCode)
            family.members.forEach { member ->
                assertTrue(
                    SkuCode(member.commodity.sku).belongsTo(spu),
                    "${member.commodity.sku} does not belong to ${family.spuCode}",
                )
            }
        }
    }

    @Test
    fun `an SPU code never steals a name another product already uses`() {
        // A derived "PL-X" would collide with the real, unrelated product called PL-X.
        val families = group(
            commodity("PL-X"),
            commodity("PL-X-1"),
            commodity("PL-X-2", children = listOf(SellfoxChild("PL-X-1", 2))),
        )

        assertEquals(setOf("PL-X", "PL-X-1"), families.map { it.spuCode }.toSet())
        assertEquals(2, families.size)
    }

    // ─── Sizes, which Sellfox does not declare at all ────────────────────

    @Test
    fun `a size run becomes one product on a Size axis`() {
        // Live data: every one of these is a plain SKU with no children, and all 274
        // gloves came in as separate products before this rule existed.
        val family = group(
            commodity("KTG-08-S"),
            commodity("KTG-08-M"),
            commodity("KTG-08-L"),
            commodity("KTG-08-XL"),
            commodity("KTG-08-2XL"),
        ).single()

        assertEquals("KTG-08", family.spuCode)
        assertEquals(SpuGrouping.Axis.SIZE, family.axis)
        // Sorted by size, not lexically: "L, M, S, XL" would be nonsense on a page.
        assertEquals(listOf("S", "M", "L", "XL", "2XL"), family.members.map { it.variantValue })
        assertTrue(family.members.all { it.packQuantity == 1 })
    }

    @Test
    fun `a lone SKU ending in a size stays its own product`() {
        // The guard that keeps "RB-05 SCREW-FT"-shaped codes out of size grouping: with
        // no sibling sizes there is no run, so there is nothing to infer.
        val families = group(commodity("RB-QF01-S"), commodity("RB-HV08-4"))

        assertEquals(setOf("RB-QF01-S", "RB-HV08-4"), families.map { it.spuCode }.toSet())
        assertTrue(families.all { it.axis == null })
    }

    @Test
    fun `a declared pack beats an inferred size`() {
        // RB-QF01-S ends in a size token and also packs into RB-QF01-S-2P. The declared
        // relationship wins, because one SPU carries one axis and only one of the two is
        // stated by the supplier.
        val families = group(
            commodity("RB-QF01-S"),
            commodity("RB-QF01-S-2P", children = listOf(SellfoxChild("RB-QF01-S", 2))),
            commodity("RB-QF01-M"),
        )

        val packed = families.single { it.spuCode == "RB-QF01-S" }
        assertEquals(SpuGrouping.Axis.PACK_QUANTITY, packed.axis)
        // RB-QF01-M is left alone rather than dragged into a size family with a base
        // that is already spoken for.
        assertEquals(setOf("RB-QF01-S", "RB-QF01-M"), families.map { it.spuCode }.toSet())
    }

    @Test
    fun `a size stem that is itself a product is not claimed`() {
        val families = group(
            commodity("KTG-08"),        // a real, separate product
            commodity("KTG-08-S"),
            commodity("KTG-08-M"),
        )

        assertEquals(3, families.size)
        assertTrue(families.all { it.axis == null })
    }

    @Test
    fun `repeated sizes are not a size run`() {
        // Two SKUs claiming "M" cannot both be the M of one product, so this is something
        // else and gets left alone.
        val families = group(commodity("A-1-M"), commodity("A-2-M"))

        assertEquals(2, families.size)
    }

    @Test
    fun `discontinued commodities are left out`() {
        val families = group(
            commodity("RB-JBX7-4", state = "4"),
            commodity("RB-HV08-4", state = "1"),
        )

        assertEquals(listOf("RB-HV08-4"), families.map { it.spuCode })
    }

    @Test
    fun `only commodities in scope are grouped`() {
        val all = listOf(
            commodity("WM7C310J255-QT4-1", cid = "WANTED-"),
            commodity("WM7C310J255-QT4-2", cid = "WANTED-", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 2))),
            commodity("BOX-001", cid = "CARTONS-"),
        )

        val families = SpuGrouping.group(all) { it.fullCid == "WANTED-" }

        assertEquals(listOf("WM7C310J255-QT4"), families.map { it.spuCode })
    }

    @Test
    fun `a pack whose base is out of scope keeps its quantity`() {
        // The base is filtered out, but the pack still knows it holds four.
        val all = listOf(
            commodity("WM7C310J255-QT4-1", cid = "CARTONS-"),
            commodity("WM7C310J255-QT4-4", cid = "WANTED-", children = listOf(SellfoxChild("WM7C310J255-QT4-1", 4))),
        )

        val family = SpuGrouping.group(all) { it.fullCid == "WANTED-" }.single()

        assertEquals(4, family.members.single().packQuantity)
        assertEquals("WM7C310J255-QT4-4", family.spuCode)
    }
}
