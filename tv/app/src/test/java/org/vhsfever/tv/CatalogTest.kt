package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against a real slice of the catalog feed and Brian's saved decisions. */
class CatalogTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val catalog = Catalog.build(res("feed-sample.json"), res("decisions-sample.json"))

    @Test fun everyGoodFileBecomesATape() {
        assertEquals(150, catalog.titles.sumOf { it.tapes.size })
    }

    @Test fun badFilesAreLeftOut() {
        val all = catalog.titles.flatMap { it.tapes }.map { it.fileId }.toSet()
        assertFalse("vhsf-program-02b3d8125608f162" in all)   // marked bad (clip)
    }

    @Test fun confirmedMovieGroupsAllItsTapes() {
        val bambi = catalog.titles.filter { it.name == "Bambi" }
        assertEquals(1, bambi.size)
        assertEquals(3, bambi[0].tapes.size)
        assertFalse(bambi[0].posterIsSuggestion)
    }

    @Test fun markedFilePlaysFirstThenBackups() {
        for (t in catalog.titles) for (tape in t.tapes) {
            assertEquals(tape.fileId, tape.sources.first().id)
            assertTrue(tape.sources.all { it.url.startsWith("https://archive.org/download/") })
        }
    }

    @Test fun cinepakAviHasNoBackupButIsKept() {
        val avi = catalog.titles.flatMap { it.tapes }.single { it.fileName == "A Bug's Life (1999 VHS).avi" }
        assertEquals("1999 VHS", avi.label)
    }

    @Test fun labels() {
        assertEquals("1992 VHS · Version 2 · Canadian Copy", Catalog.tapeLabel("VHS", "1992", "2", listOf("Canadian Copy")))
        assertEquals("1990 Tape", Catalog.tapeLabel("TAPE_UNSPECIFIED", "1990", null, emptyList()))
        assertEquals("Tape", Catalog.tapeLabel(null, null, null, emptyList()))
    }

    @Test fun tmdbSearchNames() {
        assertEquals("A Walt Disney Christmas" to null, Catalog.searchQuery("A Walt Disney Christmas (1992, UK VHS)"))
        assertEquals("Sonic the Hedgehog the Movie" to "1996", Catalog.searchQuery("1996 Sonic the Hedgehog the Movie [UPSCALED] new"))
        assertEquals("Wallace & Gromit - The Wrong Trousers" to null, Catalog.searchQuery("02 Wallace & Gromit - The Wrong Trousers"))
        assertEquals("101 Dalmatians" to null, Catalog.searchQuery("101 Dalmatians"))
    }

    @Test fun everyTitleHasSomethingToLookUp() {
        for (t in catalog.titles) assertTrue(t.name, t.tmdbId != null || t.searchName.isNotBlank())
    }
}
