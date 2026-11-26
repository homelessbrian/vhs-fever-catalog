package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Movies the bot is sure about go on the shelf by themselves, under their genres. */
class BotSureTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val noDecisions = """{"files":{}}"""

    @Test fun botSureMovieIsOnTheShelfWithItsGenres() {
        val c = Catalog.build(res("movies-feed-sample.json"), noDecisions)
        assertEquals(1, c.titles.size)               // only the one the bot is sure about
        val t = c.titles.single()
        assertEquals("Snoopy, Come Home", t.name)
        assertEquals(listOf("Animation", "Family"), t.genres)
        assertEquals(1, t.tapes.size)                // the original, with the Archive copy as a backup
        assertEquals(listOf("Animation", "Family"), c.genreShelves.map { it.first })
    }

    @Test fun markingItBadTakesItOff() {
        val c0 = Catalog.build(res("movies-feed-sample.json"), noDecisions)
        val ids = c0.titles.single().tapes.flatMap { it.sources }.map { it.id }.distinct()
        val bad = """{"files":{""" + ids.joinToString(",") { "\"$it\":{\"status\":\"bad\"}" } + "}}"
        assertTrue(Catalog.build(res("movies-feed-sample.json"), bad).titles.isEmpty())
    }
}
