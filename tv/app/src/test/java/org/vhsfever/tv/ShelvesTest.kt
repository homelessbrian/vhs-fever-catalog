package org.vhsfever.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One shelf per movie, like a real store; and which trailer plays. */
class ShelvesTest {
    private fun title(name: String, vararg g: String) =
        Title(key = name, name = name, year = null, poster = null, posterIsSuggestion = false, overview = null,
            shelf = Catalog.SHELF, tapes = emptyList(), genres = g.toList())

    @Test fun storeSections() {
        assertEquals("Horror", storeSection(listOf("Comedy", "Horror")))
        assertEquals("Animation", storeSection(listOf("Adventure", "Animation", "Family")))
        assertEquals("Science Fiction", storeSection(listOf("Action", "Thriller", "Science Fiction")))
        assertEquals("Science Fiction", storeSection(listOf("Adventure", "Action", "Science Fiction")))
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
        assertEquals("Music & Musicals", storeSection(listOf("Music", "Documentary")))
        assertEquals("Music & Musicals", storeSection(listOf("Romance", "Music")))
        assertEquals("Documentary", storeSection(listOf("Documentary", "History")))
        assertEquals("Horror", storeSection(listOf("Music", "Comedy", "Horror", "TV Movie")))
        // the shelves that used to be folded into others
        assertEquals("Action", storeSection(listOf("Action", "Thriller")))
        assertEquals("Adventure", storeSection(listOf("Adventure", "Action")))
        assertEquals("Mystery", storeSection(listOf("Mystery", "Crime")))
        assertEquals("Crime", storeSection(listOf("Crime", "Drama", "Thriller")))
        assertEquals("Crime", storeSection(listOf("Thriller", "Crime")))
        assertEquals("Crime", storeSection(listOf("Drama", "Crime")))
        assertEquals("Thriller", storeSection(listOf("Thriller", "Drama")))
        assertEquals("Romance", storeSection(listOf("Romance", "Drama")))
        assertEquals("Romance", storeSection(listOf("Drama", "Romance")))
        assertEquals("Fantasy", storeSection(listOf("Adventure", "Fantasy", "Action")))
        assertEquals("War", storeSection(listOf("War", "Action", "Drama")))
        assertEquals("War", storeSection(listOf("Drama", "War")))
        assertEquals("War", storeSection(listOf("History", "Drama", "War")))
        assertEquals("Drama", storeSection(listOf("History", "Drama")))
        assertEquals("Western", storeSection(listOf("Western", "Action")))
        assertEquals("Drama", storeSection(listOf("TV Movie", "Drama")))
        // old live-action movies go in Classics; old cartoons stay in Animation
        assertEquals("Classics", storeSection(listOf("Drama", "Romance"), "1942"))
        assertEquals("Classics", storeSection(listOf("Horror"), "1931"))
        assertEquals("Animation", storeSection(listOf("Animation", "Family"), "1950"))
        assertEquals("Romance", storeSection(listOf("Drama", "Romance"), "1960"))
        assertEquals(Catalog.OTHER, storeSection(emptyList()))
        // every shelf a movie can land on is one of Brian's
        for (g in listOf("Action", "Adventure", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History",
                "Horror", "Music", "Mystery", "Romance", "Science Fiction", "Thriller", "War", "Western", "Kids", "Reality"))
            assertTrue(storeSection(listOf(g)).let { it in STORE_SHELVES || it == Catalog.OTHER })
    }

    @Test fun shelvesFollowTheStoreOrder() {
        val c = Catalog(listOf(title("Psycho", "Horror").copy(year = "1960"), title("Alien", "Horror", "Science Fiction"),
            title("Die Hard", "Action", "Thriller"), title("Casablanca", "Drama", "Romance").copy(year = "1942")))
        assertEquals(listOf("Action", "Horror", "Classics"), c.genreShelves.map { it.first })
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
