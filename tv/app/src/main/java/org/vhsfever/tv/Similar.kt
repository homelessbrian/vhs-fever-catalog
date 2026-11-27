package org.vhsfever.tv

import kotlin.math.abs
import kotlin.math.ln

/**
 * "Similar movies" on a movie's page: every other tape in the store gets points for how much it has in
 * common with this one, and the best few are shown. In order of weight:
 *  - the same series (sequels, prequels)
 *  - TMDB pairing them ("people also liked", either way round; "similar" counts for less)
 *  - the same director, the same stars
 *  - the same theme tags (slasher, killer doll, time travel…); rare tags count for more than common ones
 *  - overlapping genres and a release year close by
 * Movies TMDB knows nothing about yet are topped up from the same shelf, like before.
 */
object Similar {
    private const val MIN_SCORE = 14.0

    fun pick(t: Title, pool: List<Title>, count: Int = 6): List<Title> {
        val others = pool.filter { it.key != t.key }
        val me = Info.of(t)
        val matched = if (me == null) emptyList() else {
            val tagCount = HashMap<Int, Int>()
            others.forEach { o -> o.art?.keywords?.toSet()?.forEach { k -> tagCount[k] = (tagCount[k] ?: 0) + 1 } }
            val n = others.size.coerceAtLeast(1)
            others.mapNotNull { o -> Info.of(o)?.let { score(me, it, tagCount, n) }?.takeIf { it >= MIN_SCORE }?.let { o to it } }
                // a little shuffle among equal scores, so the row isn't always the same order A to Z
                .shuffled().sortedByDescending { it.second }.map { it.first }.take(count)
        }
        if (matched.size >= count) return matched
        // not enough real matches: the rest from the same shelf, then anywhere
        val used = matched.map { it.key }.toSet()
        val same = others.filter { it.key !in used && it.section == t.section }.shuffled()
        val rest = others.filter { it.key !in used && it.section != t.section }.shuffled()
        return (matched + same + rest).take(count)
    }

    private class Info(val t: Title, val a: Art, val id: Int?) {
        companion object {
            fun of(t: Title): Info? {
                val a = t.art ?: return null
                return Info(t, a, a.id ?: t.tmdbId)
            }
        }
    }

    private fun score(me: Info, o: Info, tagCount: Map<Int, Int>, n: Int): Double {
        var s = 0.0
        val sameKind = me.t.tmdbType == o.t.tmdbType
        // the same series
        if (me.a.collection != null && me.a.collection == o.a.collection) s += 100
        // TMDB's pairings, either direction; higher up its list counts for more
        if (sameKind) {
            s += pairing(me.a.recs, o.id, 40.0, 20.0).coerceAtLeast(pairing(o.a.recs, me.id, 40.0, 20.0))
            s += pairing(me.a.similar, o.id, 16.0, 8.0).coerceAtLeast(pairing(o.a.similar, me.id, 16.0, 8.0))
        }
        // the same director, the same stars
        if (me.a.director != null && me.a.director == o.a.director) s += 25
        val cast = (me.a.people.ifEmpty { me.a.cast }).toSet()
        s += 12.0 * (o.a.people.ifEmpty { o.a.cast }).count { it in cast }.coerceAtMost(3)
        // shared theme tags: a tag few movies have says more than one half the store has
        val mine = me.a.keywords.toSet()
        s += o.a.keywords.toSet().filter { it in mine }.sumOf { k ->
            val have = (tagCount[k] ?: 1).toDouble()
            (ln(n / have) * 3.0).coerceIn(1.0, 12.0)
        }.coerceAtMost(36.0)
        // genres and era
        val g1 = me.t.genres.toSet(); val g2 = o.t.genres.toSet()
        if (g1.isNotEmpty() && g2.isNotEmpty()) s += 10.0 * g1.intersect(g2).size / g1.union(g2).size
        if (me.t.section == o.t.section) s += 4
        val y1 = me.t.year?.take(4)?.toIntOrNull(); val y2 = o.t.year?.take(4)?.toIntOrNull()
        if (y1 != null && y2 != null) s += when (abs(y1 - y2)) { in 0..4 -> 4.0; in 5..9 -> 2.0; else -> 0.0 }
        // a cartoon next to a slasher is a bad pairing, whatever else they share (a shared series still wins)
        if (isKidsMovie(me.t) != isKidsMovie(o.t)) s -= 30
        return s
    }

    /** Points for [id] being on [list]: [top] for the first place, sliding down to [bottom] for the last. */
    private fun pairing(list: List<Int>, id: Int?, top: Double, bottom: Double): Double {
        if (id == null) return 0.0
        val i = list.indexOf(id)
        if (i < 0) return 0.0
        return top - (top - bottom) * i / list.size.coerceAtLeast(1)
    }
}
