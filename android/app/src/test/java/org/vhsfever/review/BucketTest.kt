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
    }

    @Test fun everyShelfABucketPointsToExists() {
        val ids = BUILT_IN_SHELVES.map { it.id }.toSet()
        for (b in BUCKETS) for (fmt in FORMATS.map { it.first } + listOf<String?>(null))
            assertTrue("${b.id}/$fmt", shelfForBucket(b.id, fmt) in ids)
        assertEquals(16, BUCKETS.size)
        assertEquals("Ad reel", bucketLabel("commercial_compilation"))
    }
}
