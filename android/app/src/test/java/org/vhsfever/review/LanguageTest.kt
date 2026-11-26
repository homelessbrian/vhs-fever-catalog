package org.vhsfever.review

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTest {
    private fun foreign(name: String, tags: List<String> = emptyList()) =
        assertNotNull("should look non-English: $name", Language.foreignReason(name, tags))
    private fun english(name: String, tags: List<String> = emptyList()) =
        assertNull("should look English: $name", Language.foreignReason(name, tags))

    // Real file names from the catalog
    @Test fun realForeignNames() {
        foreign("001 - Le Grand Depart (Polish Voiceover).mp4")
        foreign("013 - La Métamorphose de Sangoku (Polish Voiceover).mp4")
        foreign("101 Dalmatians (1992 VHS) (French Canadian Copy) (Version 2).mp4")
        foreign("Pocahontas II Journey to a New World (2012 DVD) (Spanish Version).ia.mp4")
        foreign("The Lion King (2003 DVD) (French Version).mp4")
        foreign("The Three Caballeros (1995 VHS) (Spanish Copy).mp4")
        foreign("Bir Tanem 1977 Vhs Arzu Okay Türk Film.mp4")
        foreign("La belle noiseuse (1991) (1080p x265 10bit Tigole).mkv")
    }

    @Test fun realEnglishNames() {
        english("Ariel's Undersea Adventure Double Bubble - Volume 3 (1993 VHS).mp4")
        english("Gullah Gullah Island S02E05 Double Dutch.mp4")
        english("Supaidaman/27 - Farewell War Buddy - Beloved German Shepherd.mp4")
        english("Sagwa the Chinese Siamese Cat Feline Friends & Families (2003 DVD).mp4")
        english("Blue’s Big Holiday Scenes (1999).mp4")
        english("Alien³(1992)..mp4")
        english("Playboy The Story of X 100 Years of Adult Film (Screener Copy) VHS • 60 FPS 1998.mp4")
        english("01_Halloween___Horror_1978_Donald_Pleasence_Eng_Subs_720p_H264_mp4.mp4")
        english("A Bug's Life (1999 VHS) (Version 2).ia.mp4")
    }

    @Test fun otherAlphabetsAndForeignTitles() {
        foreign("Белоснежка.mp4")
        foreign("白雪姫 (1937).mp4")
        foreign("La Bella y la Bestia (1992 VHS).mp4")
        foreign("Le Roi Lion (1995 VHS).mp4")
        foreign("Der König der Löwen.mp4")
        foreign("El Rey León.mp4")
        foreign("Cenicienta (1950).mp4")
        foreign("Aladdin (Doblaje Latino).mp4")
        foreign("Godzilla Japanese with English subtitles.mp4")
        english("Pokémon The First Movie (1999 VHS).mp4")
        english("La Bamba (1987 VHS).mp4")
        english("Die Hard (1988 VHS).mp4")
        english("Cruella de Vil.mp4")
        english("Señor Droopy.mp4")
    }

    @Test fun archiveLanguageTag() {
        foreign("Some Movie.mp4", listOf("jpn"))
        foreign("Episode 1.mp4", listOf("pol", "fre"))
        english("Some Movie.mp4", listOf("eng"))
        english("Some Movie.mp4", listOf("english-handwritten"))
        english("Godzilla (English Dub).mp4", listOf("jpn"))
    }
}
