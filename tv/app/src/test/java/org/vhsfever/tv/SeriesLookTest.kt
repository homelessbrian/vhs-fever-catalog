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

    @Test fun oldCachedArtIsLookedUpAgain() {
        val old = JSONObject().put("poster", "p").put("year", "1990")
        assertEquals(false, Art.fromJson(old).collectionChecked)
        val again = Art.fromJson(Art(null, null, null, "1990", collection = 99).toJson())
        assertEquals(99, again.collection); assertTrue(again.collectionChecked)
        val none = Art.fromJson(Art(null, null, null, "1990").toJson())
        assertEquals(null, none.collection); assertTrue(none.collectionChecked)
    }
}
