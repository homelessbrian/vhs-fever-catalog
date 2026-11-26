package org.vhsfever.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketTest {
    @Test fun kindSavesAndLoads() {
        val d = FileDecision(status = "good", kind = "tape_opening")
        assertEquals("tape_opening", FileDecision.fromJson(d.toJson()).kind)
        assertEquals(null, FileDecision.fromJson(FileDecision(status = "good").toJson()).kind)
    }

    @Test fun eachKindGoesOnTheRightShelf() {
        assertEquals("vhs_release", shelfForBucket("movie", "VHS"))
        assertEquals("other_media_release", shelfForBucket("movie", "DVD"))
        assertEquals("tv_recording", shelfForBucket("tv_episode", "TV"))
        assertEquals("tv_recording", shelfForBucket("tv_recording", "VHS"))
        assertEquals("vhs_opening_or_previews", shelfForBucket("tape_opening", "VHS"))
        assertEquals("not_catalog", shelfForBucket("commercial", "TV"))
        assertEquals("home_video", shelfForBucket("home_video", null))
        assertEquals("manual_review", shelfForBucket("instructional", null))
        assertEquals("vhs_release", shelfForBucket("educational", "VHS"))
    }

    @Test fun everyShelfABucketPointsToExists() {
        val ids = BUILT_IN_SHELVES.map { it.id }.toSet()
        for (b in BUCKETS) for (fmt in FORMATS.map { it.first } + listOf<String?>(null))
            assertTrue("${b.id}/$fmt", shelfForBucket(b.id, fmt) in ids)
        assertEquals(37, BUCKETS.size)
        assertEquals(BUCKETS.size, BUCKETS.map { it.id }.toSet().size)
        assertTrue(QUICK_BUCKETS.all { q -> BUCKETS.any { it.id == q } })
        assertEquals("Ad reel", bucketLabel("commercial_compilation"))
    }

    @Test fun fourthRowShowsWhatThisFileNeeds() {
        // the robot guessed a kind that isn't up top: it gets a spot
        assertEquals(listOf("sports_bloopers"), extraBuckets(null, "sports_bloopers", emptyList()))
        // your pick first, then the robot, then recent picks; nothing twice, nothing already up top
        assertEquals(listOf("standup", "workout", "karaoke"),
            extraBuckets("standup", "workout", listOf("movie", "workout", "karaoke", "religious")))
        assertEquals(emptyList<String>(), extraBuckets("movie", "unknown", emptyList()))
    }

    @Test fun noMatchAndOwnNameSaveAndLoad() {
        val none = Movie.fromJson(Movie.noMatch().toJson())!!
        assertTrue(none.isNoMatch)
        assertEquals("No match", none.heading)
        val own = Movie.fromJson(Movie.manual("Rodeo Bloopers ", "1989").toJson())!!
        assertTrue(own.isManual)
        assertEquals("Rodeo Bloopers (1989)", own.heading)
        assertEquals(null, own.pageUrl)
        val d = FileDecision.fromJson(FileDecision(movie = Movie.noMatch()).toJson())
        assertTrue(d.movie!!.isNoMatch)
    }
}
