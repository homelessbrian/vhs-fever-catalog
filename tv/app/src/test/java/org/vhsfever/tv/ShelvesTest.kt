package org.vhsfever.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** One shelf per movie, like a real store; and which trailer plays. */
class ShelvesTest {
    private fun title(name: String, vararg g: String) =
        Title(key = name, name = name, year = null, poster = null, posterIsSuggestion = false, overview = null,
            shelf = Catalog.SHELF, tapes = emptyList(), genres = g.toList())

    @Test fun storeSections() {
        assertEquals("Horror", storeSection(listOf("Comedy", "Horror")))
        assertEquals("Animation", storeSection(listOf("Adventure", "Animation", "Family")))
        assertEquals("Sci-Fi", storeSection(listOf("Action", "Thriller", "Science Fiction")))
        assertEquals("Action", storeSection(listOf("Action", "Comedy")))
        assertEquals("Comedy", storeSection(listOf("Comedy", "Action")))
        assertEquals("Thriller", storeSection(listOf("Mystery", "Crime")))
        assertEquals("Drama", storeSection(listOf("TV Movie", "Drama")))
        assertEquals(Catalog.OTHER, storeSection(emptyList()))
    }

    @Test fun everyMovieIsOnOneShelf() {
        val c = Catalog(listOf(title("Die Hard", "Action", "Thriller"), title("Airplane!", "Comedy"),
            title("Beverly Hills Cop", "Action", "Comedy"), title("Gremlins", "Comedy", "Horror"), title("Mystery tape")))
        val shelves = c.genreShelves.toMap()
        assertEquals(c.titles.size, shelves.values.sumOf { it.size })
        assertEquals(listOf("Die Hard", "Beverly Hills Cop"), shelves["Action"]!!.map { it.name })
        assertEquals(listOf("Airplane!"), shelves["Comedy"]!!.map { it.name })
        assertEquals(listOf("Gremlins"), shelves["Horror"]!!.map { it.name })
        assertEquals(Catalog.OTHER, c.genreShelves.last().first)
    }

    @Test fun realTrailerBeforeTeasersAndClips() {
        fun v(key: String, type: String, official: Boolean = true, site: String = "YouTube") =
            JSONObject().put("key", key).put("type", type).put("official", official).put("site", site).put("iso_639_1", "en")
        val picked = Trailers.best(listOf(v("clip", "Clip"), v("teaser", "Teaser"), v("fan", "Trailer", official = false),
            v("vimeo", "Trailer", site = "Vimeo"), v("real", "Trailer"), v("bts", "Behind the Scenes")))
        assertEquals(listOf("real", "fan", "teaser"), picked)
    }
}
