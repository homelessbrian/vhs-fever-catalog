package org.vhsfever.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarTest {
    private fun movie(id: Int, year: String, vararg genres: String, art: Art.() -> Art = { this }) =
        Title("tmdb:$id", "Movie $id", year, null, false, null, "Movies", emptyList(), tmdbId = id,
            genres = genres.toList(), art = Art(null, null, null, year, id = id).art())

    @Test fun sequelComesFirst() {
        val me = movie(1, "1984", "Horror") { copy(collection = 50) }
        val sequel = movie(2, "1989", "Horror") { copy(collection = 50) }
        val others = (10..30).map { movie(it, "1985", "Horror") }
        assertEquals(sequel.key, Similar.pick(me, listOf(me, sequel) + others).first().key)
    }

    @Test fun tmdbPairingCountsEitherWay() {
        val me = movie(1, "1988", "Horror") { copy(recs = listOf(7)) }
        val liked = movie(7, "1990", "Horror")
        val likesMe = movie(8, "1991", "Horror") { copy(recs = listOf(1)) }
        val others = (10..30).map { movie(it, "1988", "Horror") }
        val picks = Similar.pick(me, listOf(me, liked, likesMe) + others).take(2).map { it.key }.toSet()
        assertEquals(setOf(liked.key, likesMe.key), picks)
    }

    @Test fun sharedRareThemeBeatsJustTheGenre() {
        val doll = 9001
        val me = movie(1, "1988", "Horror") { copy(keywords = listOf(doll, 1)) }
        val alsoDoll = movie(2, "1991", "Horror") { copy(keywords = listOf(doll, 1)) }
        // tag 1 is on everything, so it says little
        val others = (10..30).map { movie(it, "1988", "Horror") { copy(keywords = listOf(1)) } }
        assertEquals(alsoDoll.key, Similar.pick(me, listOf(me, alsoDoll) + others).first().key)
    }

    @Test fun cartoonsStayAwayFromSlashers() {
        val me = movie(1, "1988", "Horror") { copy(director = "Same Guy") }
        val cartoon = movie(2, "1988", "Animation", "Family") { copy(director = "Same Guy") }
        val horror = (10..15).map { movie(it, "1987", "Horror") }
        val picks = Similar.pick(me, listOf(me, cartoon) + horror)
        assertEquals(horror.map { it.key }.toSet(), picks.map { it.key }.toSet())
    }

    @Test fun movieWithoutTmdbInfoStillGetsARow() {
        val bare = Title("x", "Mystery tape", null, null, false, null, "Movies", emptyList())
        val picks = Similar.pick(bare, listOf(bare) + (10..20).map { movie(it, "1988", "Horror") })
        assertEquals(6, picks.size)
        assertFalse(picks.any { it.key == bare.key })
    }

    @Test fun matchInfoSurvivesTheCacheAndOldArtIsLookedUpAgain() {
        val a = Art(null, null, null, "1990", id = 5, people = listOf("A", "B"), keywords = listOf(3, 4),
            recs = listOf(9), similar = listOf(10))
        val back = Art.fromJson(a.toJson())
        assertEquals(listOf(9), back.recs); assertEquals(listOf(3, 4), back.keywords); assertEquals(5, back.id)
        assertTrue(back.matchChecked)
        assertFalse(Art.fromJson(JSONObject().put("year", "1990").put("collection", 0)).matchChecked)
    }
}
