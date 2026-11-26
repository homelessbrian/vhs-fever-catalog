package org.vhsfever.review

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun movieListFlattensToFilesWithCopiesLinked() {
        val files = parseFeed(res("feed-sample.json"))
        assertEquals(8, files.size)
        assertTrue(files.map { it.id }.toSet().size == files.size)
        val orig = files.first { it.name == "A Question of Taste (1994 UK VHS).mp4" }
        assertEquals(1, orig.copyIds.size)
        val copy = files.first { it.id == orig.copyIds[0] }
        assertTrue(copy.isCopy); assertEquals(orig.id, copy.originalId)
    }

    @Test fun botSureMovieCarriesGenres() {
        val files = parseFeed(res("feed-sample.json"))
        val sure = files.filter { it.botSure }
        assertEquals(2, sure.size)
        val f = sure.first()
        assertEquals("Snoopy, Come Home", f.titleName)
        assertEquals(12345, f.botMovie!!.tmdbId)
        assertEquals(listOf("Animation", "Family"), f.genres)
        assertFalse(files.first { it.titleName != "Snoopy, Come Home" }.botSure)
    }

    @Test fun audioParses() {
        val a = parseAudio(res("audio-sample.jsonl"))
        assertEquals(3, a.size)
        assertTrue(a.values.any { it.verdict == "silent_audio" })
    }

    @Test fun botCallParses() {
        val line = """{"file_id":"f1","verdict":"review","reason":null,"confidence":"low","verdict_why":["only weak TMDB matches"],""" +
            """"kind":"movie","format":"VHS","format_confidence":"high","format_clues":["file name says VHS"]}"""
        val g = parseRobot(line + "\n")["f1"]!!
        assertEquals("review", g.verdict); assertEquals("Not sure", g.label); assertEquals("file name says VHS", g.formatClues[0])
    }

    @Test fun olderSavedReviewsKeepTheirExtraFields() {
        val old = JSONObject("""{"status":"good","reasons":[],"shelf":"custom_xmas","tags":["Disney"],"kind":"movie",""" +
            """"updated_at":"2026-11-20T00:00:00Z","robot":"agreed_all"}""")
        val d = FileDecision.fromJson(old)
        assertEquals("good", d.status); assertEquals("agreed_all", d.robot)
        val back = d.copy(notes = "great tape").toJson()
        assertEquals("custom_xmas", back.getString("shelf"))
        assertEquals("Disney", back.getJSONArray("tags").getString(0))
        assertEquals("great tape", back.getString("notes"))
        assertNull(FileDecision.fromJson(FileDecision(status = "good").toJson()).robot)
    }

    @Test fun adultTitleMarksItsFiles() {
        val feed = JSONObject(res("feed-sample.json"))
        feed.getJSONArray("titles").getJSONObject(0).put("adult", true)
        val files = parseFeed(feed.toString())
        val first = feed.getJSONArray("titles").getJSONObject(0).optString("id")
        assertTrue(files.filter { it.titleId == first }.all { it.adult })
        assertTrue(files.filter { it.titleId != first }.none { it.adult })
    }
}
