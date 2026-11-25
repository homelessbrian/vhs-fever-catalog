package org.vhsfever.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SplitTest {
    private fun file(name: String, dur: Double? = 3600.0, part: String? = null, item: String = "item") = FileItem(
        id = "$item/$name", name = name, archiveItem = item, role = "original", format = null, url = "", durationSec = dur,
        recordingId = "r", versionLabel = null, partLabel = part, modelShelf = "manual_review", modelReasons = emptyList(),
        titleId = "t", titleName = "t", editionId = null, media = null, releaseYear = null, editionNotes = emptyList(),
        tmdbStatus = null, tmdbCeiling = null, suggestions = emptyList())

    private fun split(f: FileItem) = assertNotNull("should be split: ${f.name}", Split.reason(f))
    private fun whole(f: FileItem) = assertNull("should be whole: ${f.name}", Split.reason(f))

    // Real file names from the catalog
    @Test fun joinedParts() = split(file("101 Dalmatians (1992 VHS) - 03of20.ia.mp4", 235.0, "Part 3 of 20"))

    @Test fun strayPieces() {
        split(file("The Little Mermaid (1990 VHS) - 15of22.ia.mp4", 121.0))
        split(file("Some Movie Part 2 of 6.mp4", 900.0))
        split(file("Some Movie (2/12).mp4", null))
        split(file("Some Movie 1 of 2.mp4", 600.0))
    }

    @Test fun wholeVideosStay() {
        whole(file("Back to the future/Back to the Future Part 2.mp4", 6527.0))
        whole(file("Blue's Clues S04E95B Joe Gets a Clue (Part 2 of 3).mp4", 1473.0))
        whole(file("Blue's Clues - Meet Joe Part 1 Restoration (Joe and Tell).mkv", 1488.0))
        whole(file("Beauty and the Beast (Disc 1).ia.mp4", 5095.0))
        whole(file("Two-tape Movie 1 of 2.mp4", 3600.0))
        whole(file("The Maxx/Season 1/The Maxx - S01E01 - Chapter 1.mkv", 736.0))
        whole(file("Top 10 of 1985.mp4", 600.0))
    }

    @Test fun dvdChunks() {
        val files = listOf(
            file("Traces Of Death 1/VIDEO_TS/VTS_01_1.VOB", 1361.0),
            file("Traces Of Death 1/VIDEO_TS/VTS_01_2.VOB", 1361.0),
            file("Traces Of Death 1/VIDEO_TS/VTS_01_0.VOB", 0.0),
            file("Other/VIDEO_TS/VTS_01_1.VOB", 5000.0),
        )
        val found = Split.find(files)
        assertEquals(setOf("item/Traces Of Death 1/VIDEO_TS/VTS_01_1.VOB", "item/Traces Of Death 1/VIDEO_TS/VTS_01_2.VOB"), found.keys)
    }
}
