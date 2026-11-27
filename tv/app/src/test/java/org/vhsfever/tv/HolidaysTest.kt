package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Holiday displays: when they go up and come down, and which tapes go on them. */
class HolidaysTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun names(day: String) = Holidays.active(d(day)).map { it.name }
    private fun title(name: String, overview: String? = null, tags: List<String> = emptyList(), adult: Boolean = false) =
        Title(key = name, name = name, year = null, poster = null, posterIsSuggestion = false, overview = overview,
            shelf = Catalog.SHELF, tapes = emptyList(), adult = adult,
            art = Art(null, null, null, null, tags = tags))

    @Test fun movingHolidays() {
        assertEquals(d("2026-11-26"), Holidays.ALL.first { it.name == "Thanksgiving" }.date(2026))
        assertEquals(d("2027-11-25"), Holidays.ALL.first { it.name == "Thanksgiving" }.date(2027))
        assertEquals(d("2026-04-05"), Holidays.easter(2026))
        assertEquals(d("2027-03-28"), Holidays.easter(2027))
        assertEquals(d("2024-03-31"), Holidays.easter(2024))
    }

    @Test fun upThreeWeeksBeforeAndAWeekAfter() {
        assertEquals(listOf<String>(), names("2026-10-09"))
        assertEquals(listOf("Halloween"), names("2026-10-10"))        // 21 days before
        // a week after Halloween it's still up, and Thanksgiving's display (from Nov 5) is up too
        assertEquals(listOf("Halloween", "Thanksgiving"), names("2026-11-07"))
        assertEquals(listOf("Thanksgiving"), names("2026-11-08"))     // Halloween is down, Thanksgiving is up
        // Thanksgiving itself is up; the next morning it comes down and Christmas goes straight up
        assertEquals(listOf("Thanksgiving"), names("2026-11-26"))
        assertEquals(listOf("Christmas"), names("2026-11-27"))
        assertEquals(listOf("Christmas"), names("2026-12-03"))
        assertEquals(listOf("Thanksgiving"), names("2027-11-25"))
        assertEquals(listOf("Christmas"), names("2027-11-26"))   // Thanksgiving 2027 is Nov 25
        // between Christmas and New Year's both are up; the nearer one leads
        assertEquals(listOf("Christmas", "New Year's"), names("2026-12-27"))
        assertEquals(listOf("New Year's", "Christmas"), names("2026-12-30"))
        assertEquals(listOf("New Year's"), names("2027-01-08"))
        assertEquals(listOf<String>(), names("2027-01-09"))
        assertEquals(listOf("4th of July"), names("2027-06-20"))
    }

    @Test fun managersPicksAndCultClassics() {
        val c = Catalog(listOf(
            title("A Christmas Story").copy(managerPick = true),
            title("Repo Man", tags = listOf("Cult Film", "punk")),
            title("Airplane!")))
        assertEquals(listOf("A Christmas Story"), c.shelfTitles(Catalog.MANAGERS_PICKS).map { it.name })
        assertEquals(listOf("Repo Man"), c.shelfTitles(Catalog.CULT_CLASSICS).map { it.name })
        assertEquals(listOf("Christmas", Catalog.MANAGERS_PICKS, Catalog.CULT_CLASSICS), c.displays(d("2026-12-10")).map { it.first })
        assertEquals(listOf<String>(), Catalog(listOf(title("Airplane!"))).displays(d("2026-12-10")).map { it.first })
    }

    @Test fun whatGoesOnTheDisplay() {
        val xmas = Holidays.ALL.first { it.name == "Christmas" }
        val halloween = Holidays.ALL.first { it.name == "Halloween" }
        assertTrue(Holidays.matches(title("101 Dalmatians Christmas"), xmas))
        assertTrue(Holidays.matches(title("Die Hard", tags = listOf("christmas party", "skyscraper")), xmas))
        assertTrue(Holidays.matches(title("Gremlins", "A boy gets a strange pet for Christmas."), xmas))
        assertTrue(Holidays.matches(title("Ernest Saves", "Santa Claus needs a replacement."), xmas))
        assertFalse(Holidays.matches(title("Santa Fe Trail", "Cavalry heads west to Santa Fe."), xmas))
        assertFalse(Holidays.matches(title("Christmas tape", adult = true), xmas))
        assertTrue(Holidays.matches(title("Halloween III: Season of the Witch"), halloween))
        assertTrue(Holidays.matches(title("Hocus Pocus", tags = listOf("witch", "halloween")), halloween))
        assertFalse(Holidays.matches(title("The Thing", tags = listOf("antarctica")), halloween))
        assertFalse(Holidays.matches(title("Mysterious Island", "Castaways reach Easter Island."), Holidays.ALL.first { it.name == "Easter" }))
    }

    @Test fun holidayShelvesOnlyWithTapes() {
        val c = Catalog(listOf(title("A Christmas Story"), title("Airplane!")))
        assertEquals(listOf("Christmas"), c.holidayShelves(d("2026-12-10")).map { it.first.name })
        assertEquals(listOf("A Christmas Story"), c.shelfTitles("Christmas").map { it.name })
        assertEquals(listOf<String>(), c.holidayShelves(d("2026-11-15")).map { it.first.name })  // no Thanksgiving tapes
    }
}
