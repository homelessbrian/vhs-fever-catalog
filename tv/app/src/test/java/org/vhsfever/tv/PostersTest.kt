package org.vhsfever.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostersTest {
    @Test fun addressUsesTheImdbId() {
        assertEquals("https://btttr.cc/poster-n/imdb/poster-default/tt0093773.jpg?tag=none", PosterRepo.url("tt0093773"))
    }

    @Test fun onlyRealImdbIds() {
        assertTrue(PosterRepo.validImdb("tt0093773"))
        assertTrue(PosterRepo.validImdb("tt12345678"))
        assertFalse(PosterRepo.validImdb("ttabc"))
        assertFalse(PosterRepo.validImdb("603"))
        assertFalse(PosterRepo.validImdb("tt0093773/../x"))
    }

    @Test fun aPictureIsAPoster() {
        assertTrue(PosterRepo.judge(200, "image/webp", ByteArray(70000)) is PosterRepo.Got.Poster)
        assertTrue(PosterRepo.judge(200, "image/jpeg", ByteArray(5000)) is PosterRepo.Got.Poster)
    }

    @Test fun aRedirectMeansNoPoster() {
        // btttr.cc sends movies it has no poster for on to another site
        assertEquals(PosterRepo.Got.None, PosterRepo.judge(302, "text/plain; charset=utf-8", null))
        assertEquals(PosterRepo.Got.None, PosterRepo.judge(404, "text/html", null))
    }

    @Test fun anythingOddIsTriedAgainLater() {
        assertEquals(PosterRepo.Got.TryLater, PosterRepo.judge(500, "text/html", null))
        assertEquals(PosterRepo.Got.TryLater, PosterRepo.judge(200, "text/html", ByteArray(9000)))
        assertEquals(PosterRepo.Got.TryLater, PosterRepo.judge(200, "image/webp", ByteArray(100)))
        assertEquals(PosterRepo.Got.TryLater, PosterRepo.judge(429, null, null))
    }
}
