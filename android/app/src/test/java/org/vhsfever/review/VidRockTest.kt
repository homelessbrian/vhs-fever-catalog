package org.vhsfever.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VidRockTest {
    @Test fun buildsMovieEmbedUrlFromValidImdbId() {
        assertEquals("https://vidrock.to/movie/tt0096895", VidRock.movieUrl(" tt0096895 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidImdbId() {
        VidRock.movieUrl("not-an-imdb-id")
    }

    @Test fun eventExportsStructuredMetadata() {
        val json = VidRockEvent("2026-11-28T00:00:00Z", "resource-request",
            "https://cdn.example.test/movie/master.m3u8", "GET").toJson()
        assertEquals("resource-request", json.getString("kind"))
        assertTrue(json.getString("url").endsWith(".m3u8"))
    }

    @Test fun probeIncludesFetchXhrAndMediaHooks() {
        assertTrue(VidRock.instrumentationScript.contains("window.fetch"))
        assertTrue(VidRock.instrumentationScript.contains("XMLHttpRequest"))
        assertTrue(VidRock.instrumentationScript.contains("PerformanceObserver"))
        assertTrue(VidRock.instrumentationScript.contains("play-event"))
    }
}
