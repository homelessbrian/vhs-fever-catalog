package org.vhsfever.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun feedFlattensToFilesWithCopiesLinked() {
        val files = parseFeed(res("feed-sample.json"))
        assertEquals(52, files.size)
        val v2 = files.first { it.name == "A Goofy Movie (2000 vHS).mp4" }
        assertEquals("VHS", v2.media); assertEquals("2000", v2.releaseYear)
        assertEquals(1, v2.copyIds.size)
        val copy = files.first { it.id == v2.copyIds[0] }
        assertTrue(copy.isCopy); assertEquals(v2.id, copy.originalId)
        assertTrue(files.map { it.id }.toSet().size == files.size)
        val avi = files.first { it.name == "101 Dalmatians (1992 VHS).avi" }
        assertFalse(avi.phonePlayable)
        val parts = files.filter { it.partLabel != null && !it.isCopy }
        assertEquals(3, parts.size)
    }

    @Test fun audioParses() {
        val a = parseAudio(res("audio-sample.jsonl"))
        assertEquals(3, a.size)
        assertTrue(a.values.any { it.verdict == "silent_audio" })
    }

    @Test fun robotGuessesAndDecisionMarkerRoundTrip() {
        val line = """{"file_id":"f1","verdict":"bad","reason":"Clip","confidence":"high","verdict_why":["piece of a video split into parts"],""" +
            """"kind":"clip","kind_why":["piece 3 of 20"],"format":"VHS","format_confidence":"high","format_clues":["file name says VHS"],""" +
            """"shelf":"not_catalog","builtin_commercials":"unknown"}"""
        val g = parseRobot(line + "\n")["f1"]!!
        assertEquals("bad", g.verdict); assertEquals("Bad: Clip", g.label); assertEquals("VHS", g.format)
        val d = FileDecision(status = "bad", reasons = listOf("Clip"), robot = "agreed_all")
        assertEquals("agreed_all", FileDecision.fromJson(d.toJson()).robot)
        assertEquals(null, FileDecision.fromJson(FileDecision(status = "good").toJson()).robot)
    }
}
