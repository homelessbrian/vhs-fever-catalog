package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTest {
    private val keys = (1..400).map { "tmdb:${it * 37 + 11}" }

    @Test fun sameBoxAlwaysLooksTheSame() {
        for (f in WearFace.values()) assertEquals(WearPlan.plan("tmdb:603", f), WearPlan.plan("tmdb:603", f))
    }

    @Test fun boxesDontAllLookAlike() {
        val looks = keys.map { k -> WearPlan.plan(k, WearFace.Front).map { m -> m.javaClass.simpleName }.sorted() }.toSet()
        assertTrue("only ${looks.size} different mixes", looks.size >= 25)
        // and the same kinds of wear still land in different places
        val corners = keys.mapNotNull { k -> WearPlan.plan(k, WearFace.Front).filterIsInstance<WearMark.Corner>().firstOrNull()?.corner }.toSet()
        assertEquals(setOf(0, 1, 2, 3), corners)
    }

    @Test fun someNearlyCleanSomeWellLoved() {
        val counts = keys.map { WearPlan.plan(it, WearFace.Front).size }
        assertTrue(counts.count { it <= 1 } >= 10)
        assertTrue(counts.count { it >= 4 } >= 20)
        assertTrue(counts.all { it <= 12 })
    }

    @Test fun spinesGetSpineWearOnly() {
        for (k in keys) {
            val marks = WearPlan.plan(k, WearFace.Spine)
            assertTrue(marks.none { it is WearMark.Sticker || it is WearMark.Tape || it is WearMark.Crease || it is WearMark.Smudge })
            assertTrue(marks.filterIsInstance<WearMark.Edge>().all { it.edge == 1 || it.edge == 3 })
        }
        assertTrue(keys.any { k -> WearPlan.plan(k, WearFace.Spine).any { it is WearMark.Rub } })
    }

    @Test fun wearStaysOnTheBox() {
        for (k in keys) for (f in WearFace.values()) for (m in WearPlan.plan(k, f)) {
            assertTrue(m.s in 0f..1f)
            when (m) {
                is WearMark.Edge -> assertTrue(m.from in 0f..1f && m.to in m.from..1f)
                is WearMark.Rub -> assertTrue(m.from in 0f..1f && m.to in m.from..1f)
                is WearMark.Sticker -> assertTrue(m.x in 0f..1f && m.y in 0f..1f)
                else -> Unit
            }
        }
    }

    @Test fun sunFadedBoxesFadeOnEverySide() {
        val faded = keys.filter { k -> WearPlan.plan(k, WearFace.Spine).any { it is WearMark.Fade } }
        assertTrue(faded.size in 40..140)
        for (k in faded) assertTrue(WearPlan.plan(k, WearFace.Front).any { it is WearMark.Fade })
    }
}
