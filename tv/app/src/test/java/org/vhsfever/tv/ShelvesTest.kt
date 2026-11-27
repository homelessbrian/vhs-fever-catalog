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
        assertEquals("Sci-Fi", storeSection(listOf("Adventure", "Action", "Science Fiction")))
        // Dude, Where's My Car? and friends: a comedy first, whatever the premise
        assertEquals("Comedy", storeSection(listOf("Comedy", "Science Fiction")))
        assertEquals("Comedy", storeSection(listOf("Adventure", "Comedy", "Science Fiction")))
        assertEquals("Comedy", storeSection(listOf("Action", "Comedy", "Crime")))
        assertEquals("Comedy", storeSection(listOf("Comedy", "Action")))
        assertEquals("Comedy", storeSection(listOf("Romance", "Comedy", "Drama")))
        assertEquals("Comedy", storeSection(listOf("Western", "Comedy")))
        assertEquals("Drama", storeSection(listOf("Drama", "Comedy")))
        assertEquals("Family", storeSection(listOf("Comedy", "Family")))
        assertEquals("Family", storeSection(listOf("Music", "Comedy", "Family", "Fantasy", "Drama")))
        assertEquals("Music", storeSection(listOf("Music", "Documentary")))
        assertEquals("Horror", storeSection(listOf("Music", "Comedy", "Horror", "TV Movie")))
        assertEquals("Thriller", storeSection(listOf("Mystery", "Crime")))
        assertEquals("Thriller", storeSection(listOf("Crime", "Drama", "Thriller")))
        assertEquals("Drama", storeSection(listOf("Crime", "Drama")))
        assertEquals("Action", storeSection(listOf("War", "Action", "Drama")))
        assertEquals("Drama", storeSection(listOf("War", "Drama")))
        assertEquals("Drama", storeSection(listOf("TV Movie", "Drama")))
        assertEquals(Catalog.OTHER, storeSection(emptyList()))
    }

    @Test fun everyMovieIsOnOneShelf() {
        val c = Catalog(listOf(title("Die Hard", "Action", "Thriller"), title("Airplane!", "Comedy"),
            title("Beverly Hills Cop", "Action", "Comedy"), title("Gremlins", "Comedy", "Horror"), title("Mystery tape")))
        val shelves = c.genreShelves.toMap()
        assertEquals(c.titles.size, shelves.values.sumOf { it.size })
        assertEquals(listOf("Die Hard"), shelves["Action"]!!.map { it.name })
        assertEquals(listOf("Airplane!", "Beverly Hills Cop"), shelves["Comedy"]!!.map { it.name })
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
