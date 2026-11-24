package org.vhsfever.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EpisodeTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val eps = EpisodeMatch.numberAbsolute(listOf(
        Episode(1, 14, "Read Me a Story"), Episode(2, 13, "Copy Cat, Copy Cat Go Away"),
        Episode(2, 15, "Strange Vet Fellows", "1995-03-01"), Episode(3, 1, "Monty Joins the Class / Dad Goes to School"),
        Episode(0, 1, "Holiday Special")))

    @Test fun matchesByNumberAndTitle() {
        val h = EpisodeMatch.match(eps, TvHint("Allegra's Window", 2, 15, "Strange Vet Fellows", null, false)).first()
        assertEquals("Strange Vet Fellows", h.episode.name)
        assertTrue(h.strong)
        assertTrue("episode title" in h.basis && "season and episode number" in h.basis)
    }

    @Test fun titleWinsWhenNumberIsOff() {
        val hits = EpisodeMatch.match(eps, TvHint("Allegra's Window", 2, 13, "Strange Vet Fellows", null, false))
        assertEquals("Strange Vet Fellows", hits.first().episode.name)
        assertFalse(hits.first { it.episode.name.startsWith("Copy") }.strong)
    }

    @Test fun showDashEpisodeAndTwoStories() {
        assertEquals("Copy Cat, Copy Cat Go Away",
            EpisodeMatch.match(eps, TvHint("Allegra's Window", null, null, "Copy Cat Copy Cat Go Away", null, true)).first().episode.name)
        val two = EpisodeMatch.match(eps, TvHint("Little Bill", null, null, "Dad Goes To School", null, false)).first()
        assertEquals(3, two.episode.season)
        assertTrue(EpisodeMatch.similarity("The Messy Room", "Messy Room") > 0.95)
        assertTrue(EpisodeMatch.similarity("Toast", "Becca and the Band") < 0.5)
    }

    @Test fun absoluteNumberAndAirDate() {
        assertEquals(3, eps.first { it.name == "Strange Vet Fellows" }.absolute)
        assertNull(eps.first { it.season == 0 }.absolute)
        assertEquals("Strange Vet Fellows", EpisodeMatch.match(eps, TvHint("X", null, 3, null, null, false)).first().episode.name)
        assertEquals("Strange Vet Fellows", EpisodeMatch.match(eps, TvHint("X", null, null, null, "1995-03-01", false)).first().episode.name)
    }

    @Test fun feedCarriesEpisodeSuggestions() {
        val files = parseFeed(res("feed-episodes.json"))
        val vet = files.first { it.titleName == "S02E15 Strange Vet Fellows" }
        assertNotNull(vet.tv); assertEquals(2, vet.tv!!.season); assertEquals(15, vet.tv!!.episode)
        val s = vet.suggestions.first()
        assertEquals("Allegra's Window", s.title)
        assertEquals("S02E15 · Strange Vet Fellows", s.episode!!.label)
        val dash = files.first { it.titleName.startsWith("Gullah Gullah Island - Beat It") }
        assertTrue(dash.tv!!.fromDash); assertEquals("Gullah Gullah Island", dash.tv!!.show)
        assertEquals(3, dash.suggestions.first().episode!!.season)
    }

    @Test fun chosenEpisodeSurvivesSaving() {
        val m = Movie(1, "tv", "Allegra's Window", "1994", null, episode = Episode(2, 15, "Strange Vet Fellows"), source = "tvdb", tvdbId = 77)
        val back = Movie.fromJson(JSONObject(m.toJson().toString()))!!
        assertEquals("S02E15", back.episode!!.code); assertEquals("tvdb", back.source); assertEquals(77, back.tvdbId)
        assertNull(Movie.fromJson(JSONObject(Movie(5, "movie", "A", null, null).toJson().toString()))!!.episode)
    }
}
