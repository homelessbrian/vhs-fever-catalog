package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class SeriesLookTest {
    private fun movie(key: String, collection: Int?) = Title(key, key, "1990", null, false, null, "Movies", emptyList(),
        art = Art(null, null, null, "1990", collection = collection))

    @Test fun sequelsShareOneLook() {
        val films = (1..40).map { movie("tmdb:${it * 53 + 7}", 1234) }
        assertEquals(1, films.map { sleeveStyle(it) }.toSet().size)
        assertEquals(1, films.map { backLayout(it) }.toSet().size)
    }

    @Test fun movieWithoutSeriesStillVaries() {
        val films = (1..60).map { movie("tmdb:${it * 53 + 7}", null) }
        assertTrue(films.map { sleeveStyle(it) }.toSet().size >= 8)
    }

    private fun film(key: String, collection: Int?, vararg genres: String) =
        movie(key, collection).copy(genres = genres.toList())

    @Test fun kidsMoviesGetOnlyKidsLooks() {
        val titles = withKidsLooks((1..60).map { film("tmdb:${it * 41 + 3}", null, "Animation", "Family") } +
            (1..60).map { film("tmdb:${it * 43 + 5}", null, "Horror") })
        val (kids, grown) = titles.partition { it.kidsLook }
        assertEquals(60, kids.size)
        assertTrue(kids.all { sleeveStyle(it) in KIDS_LOOKS })
        assertEquals(KIDS_LOOKS.toSet(), kids.map { sleeveStyle(it) }.toSet())
        assertTrue(grown.none { sleeveStyle(it) in KIDS_LOOKS })
    }

    @Test fun aSeriesWithAKidsMovieIsAllKids() {
        val titles = withKidsLooks(listOf(film("tmdb:1", 77, "Family", "Comedy"), film("tmdb:2", 77, "Comedy"),
            film("tmdb:3", 77, "Adventure", "Action"), film("tmdb:4", 88, "Comedy")))
        assertEquals(listOf(true, true, true, false), titles.map { it.kidsLook })
        assertEquals(1, titles.take(3).map { sleeveStyle(it) }.toSet().size)
        assertEquals(1, titles.take(3).map { kidsBackLayout(it) }.toSet().size)
    }

    @Test fun oldCachedArtIsLookedUpAgain() {
        val old = JSONObject().put("poster", "p").put("year", "1990")
        assertEquals(false, Art.fromJson(old).collectionChecked)
        val again = Art.fromJson(Art(null, null, null, "1990", collection = 99).toJson())
        assertEquals(99, again.collection); assertTrue(again.collectionChecked)
        val none = Art.fromJson(Art(null, null, null, "1990").toJson())
        assertEquals(null, none.collection); assertTrue(none.collectionChecked)
    }
}
