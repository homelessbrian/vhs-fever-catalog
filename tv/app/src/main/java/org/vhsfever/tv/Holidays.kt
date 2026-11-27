package org.vhsfever.tv

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A holiday display, like the seasonal rack at the front of a rental store. A movie on the display
 * still sits on its own genre shelf too: the display is extra, not a shelf.
 *  - [date] works out the holiday's day in a given year (Thanksgiving and Easter move around).
 *  - [tags] are TMDB theme tags that mean the movie is about the holiday.
 *  - [words] are words that mean the same when they're in the title or the summary.
 */
class Holiday(
    val name: String,
    val date: (Int) -> LocalDate,
    val tags: Set<String>,
    val words: Regex,
    /** The first day the display is up, for a holiday that doesn't go up the usual 3 weeks before. */
    val opens: ((Int) -> LocalDate)? = null,
    /** How many days after the holiday the display stays up. */
    val after: Long = Holidays.AFTER,
)

object Holidays {
    /** The display goes up this many days before the holiday… */
    const val BEFORE = 21L
    /** …and comes down this many days after it. */
    const val AFTER = 7L

    private fun words(vararg w: String) = Regex("\\b(" + w.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    val ALL = listOf(
        Holiday("New Year's", { y -> LocalDate.of(y, 1, 1) },
            setOf("new year's eve", "new year", "new year's day", "new year's party", "new year's resolution"),
            words("new year'?s eve", "new year'?s day", "new year'?s party")),
        Holiday("Valentine's Day", { y -> LocalDate.of(y, 2, 14) },
            setOf("valentine's day", "valentine"),
            words("valentine'?s day", "valentine'?s")),
        Holiday("St. Patrick's Day", { y -> LocalDate.of(y, 3, 17) },
            setOf("saint patrick's day", "st. patrick's day", "leprechaun"),
            words("st\\.? patrick'?s", "saint patrick'?s", "leprechauns?")),
        Holiday("Easter", ::easter,
            setOf("easter", "easter bunny", "easter egg hunt", "resurrection of jesus"),
            words("easter(?! island)")),
        Holiday("4th of July", { y -> LocalDate.of(y, 7, 4) },
            setOf("independence day", "fourth of july", "4th of july", "independence day (usa)"),
            words("fourth of july", "4th of july", "independence day")),
        Holiday("Halloween", { y -> LocalDate.of(y, 10, 31) },
            setOf("halloween", "trick or treating", "jack-o'-lantern", "halloween party", "halloween costume"),
            words("halloween", "trick[- ]or[- ]treat\\w*", "jack-o'?-lanterns?")),
        // Like a real store, Thanksgiving comes down the next morning and Christmas goes straight up.
        Holiday("Thanksgiving", ::thanksgiving,
            setOf("thanksgiving", "thanksgiving dinner", "thanksgiving day"),
            words("thanksgiving"), after = 0),
        Holiday("Christmas", { y -> LocalDate.of(y, 12, 25) },
            setOf("christmas", "christmas eve", "christmas party", "christmas tree", "santa claus", "christmas carol",
                "christmas gift", "christmas spirit", "christmas holiday", "scrooge", "grinch",
                "christmas morning", "christmas lights", "nativity of jesus", "christmas elf"),
            // plain "Santa" would catch Santa Fe and Santa Monica
            words("christmas", "x-?mas", "santa claus", "santa'?s", "the santa clause", "scrooge", "grinch", "yuletide",
                "rudolph the red-nosed")),
            opens = { y -> thanksgiving(y).plusDays(1) }),
    )

    /** Thanksgiving: the fourth Thursday of November. */
    fun thanksgiving(y: Int): LocalDate = LocalDate.of(y, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))

    /** Easter Sunday (the Western church's reckoning). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }

    /** The day of [h] nearest to [today] (last year's, this year's or next year's). */
    fun nearest(h: Holiday, today: LocalDate): LocalDate =
        (today.year - 1..today.year + 1).map { h.date(it) }.minBy { Math.abs(ChronoUnit.DAYS.between(today, it)) }

    /** Holidays whose display is up on [today], the nearest one first (an upcoming one wins a tie). */
    fun active(today: LocalDate): List<Holiday> = ALL.mapNotNull { h ->
        val d = nearest(h, today)
        val until = ChronoUnit.DAYS.between(today, d)  // days to go; negative once it's passed
        val first = h.opens?.invoke(d.year) ?: d.minusDays(BEFORE)
        if (!today.isBefore(first) && until >= -h.after) h to (if (until >= 0) until * 2 else -until * 2 + 1) else null
    }.sortedBy { it.second }.map { it.first }

    /** Whether [t] is about [h]: TMDB tags it so, or the holiday is named in its title or summary. */
    fun matches(t: Title, h: Holiday): Boolean {
        if (t.adult) return false
        val tags = t.art?.tags.orEmpty()
        if (tags.any { it in h.tags }) return true
        return h.words.containsMatchIn(t.name) || (t.overview ?: t.art?.overview)?.let { h.words.containsMatchIn(it) } == true
    }
}
