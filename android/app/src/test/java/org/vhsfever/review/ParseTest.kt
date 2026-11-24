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
}
