package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copies of one recording are one tape with backups; the bot's names and order are used. */
class TapeCopiesTest {
    private fun src(id: String, tg: String, pk: Int, lab: String? = null, item: String = "page-$id",
                    role: String = "original", hide: Boolean = false, ok: Boolean = true) =
        """{"id":"$id","ok":$ok,"role":"$role","n":"$id.mp4","item":"$item","dur":5040,"fmt":"MPEG4",
            "tg":"$tg","pk":$pk${lab?.let { ",\"lab\":\"$it\"" } ?: ""}${if (hide) ",\"hide\":true" else ""}}"""

    private val feed = """{"titles":[{"id":"t1","t":"101 Dalmatians","genres":["Animation"],
        "movie":{"tmdb_id":12230,"title":"One Hundred and One Dalmatians","year":"1961"},
        "eds":[{"media":"VHS","year":"1992","recs":[
          {"segs":[{"src":[${src("a1", "g1", 0, "1992 VHS · Full screen")},${src("a1c", "g1", 1, role = "archive_derivative", item = "page-a1")}]}]},
          {"segs":[{"src":[${src("b1", "g1", 2, ok = false)}]}]},
          {"segs":[{"src":[${src("c1", "g2", 1000, "1992 VHS · Zoomed")}]}]},
          {"segs":[{"src":[${src("d1", "g3", 2000, "1992 VHS · Full screen · No sound", hide = true)}]}]}
        ]}]}]}"""
    private val none = """{"files":{}}"""

    @Test fun copiesOfOneRecordingAreOneTapeWithBackups() {
        val t = Catalog.build(feed, none).titles.single()
        assertEquals(listOf("1992 VHS · Full screen", "1992 VHS · Zoomed"), t.tapes.map { it.label })  // silent one not listed
        // the bot's best copy first, then its Archive copy, then the same recording on another page
        assertEquals(listOf("a1", "a1c", "b1"), t.tapes[0].sources.map { it.id })
    }

    @Test fun theCopyBrianPicksPlaysFirst() {
        val d = """{"files":{"c1":{"status":"good","play_first":true,"updated_at":"2026-11-27T12:00:00Z"}}}"""
        val t = Catalog.build(feed, d).titles.single()
        assertEquals("1992 VHS · Zoomed", t.tapes[0].label)
        assertTrue(t.tapes[0].pinnedAt != null)
    }

    @Test fun aCopyMarkedBadIsNotABackup() {
        val d = """{"files":{"b1":{"status":"bad","reasons":["Won’t play"]}}}"""
        val ids = Catalog.build(feed, d).titles.single().tapes[0].sources.map { it.id }
        assertFalse("b1" in ids)
    }

    @Test fun aSilentTapeIsListedWhenItIsTheOnlyOne() {
        val only = """{"titles":[{"id":"t1","t":"X","movie":{"tmdb_id":1,"title":"X"},
            "un":[{"segs":[{"src":[${src("d1", "g3", 0, "VHS · No sound", hide = true)}]}]}]}]}"""
        assertEquals(1, Catalog.build(only, none).titles.single().tapes.size)
    }
}
