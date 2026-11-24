package org.vhsfever.review

/**
 * Decides whether a file is probably not in English.
 *
 * It looks at two things:
 *  1. the language the Archive item is tagged with (if any), and
 *  2. the file name itself: other alphabets, words like "Spanish Version" / "Doblaje",
 *     and titles written in French, Spanish, German, etc.
 *
 * A file name that says it is English ("English Dub", "(English)") always wins.
 * Returns a short human reason, or null when the file looks English.
 */
object Language {
    private const val LANGS = "spanish|espa[nñ]ol|castellano|latino|french|fran[cç]ais|qu[eé]b[eé]cois|german|deutsch|" +
        "italian|italiano|portuguese|portugu[eê]s|brazilian|polish|polski|russian|japanese|chinese|mandarin|cantonese|" +
        "korean|dutch|flemish|swedish|norwegian|danish|finnish|turkish|t[uü]rk[cç]e|hindi|tamil|telugu|arabic|hebrew|" +
        "greek|hungarian|magyar|czech|slovak|romanian|bulgarian|serbian|croatian|ukrainian|thai|vietnamese|tagalog|" +
        "filipino|indonesian|malay|persian|farsi"
    private const val MARK = "version|copy|dub|dubbed|dubbing|voice ?over|audio|lektor|language|track|edition|release|" +
        "subs?|subtitles?|subtitled|film|tape|vhs|dvd|cut"
    private val I = RegexOption.IGNORE_CASE

    private val strong = listOf(
        Regex("\\b($LANGS)\\s+(canadian\\s+)?($MARK)\\b", I),              // "Spanish Version", "Polish Voiceover"
        Regex("[(\\[]\\s*($LANGS)\\s*[)\\]]", I),                          // "(Spanish)"
        Regex("\\b(in|en)\\s+($LANGS)\\b", I),                             // "in Spanish", "en español"
        Regex("\\b($LANGS)\\b.*\\b(subs?|subtitles?|subbed|subtitled)\\b", I), // "Japanese with English subtitles"
        Regex("\\b(doblad[oa]|doblaje|dublado|dublagem|legendado|subtitulad[oa]|doublage|doublé|vostfr|vost|synchro|" +
            "lektor|napisy|t[uü]rk filmi?|audio latino)\\b", I),
        Regex("\\b(VF|VFF|VFQ|VOSE)\\b"),
    )

    private val englishAudio = Regex("\\benglish\\s+(dub|dubbed|version|audio|language|track|voice ?over)\\b|" +
        "[(\\[]\\s*english\\s*[)\\]]|\\bin english\\b|\\beng ?dub\\b", I)

    /** Well-known foreign titles that give the language away on their own. */
    private val tellTale = setOf(
        "blancanieves", "cenicienta", "cendrillon", "aschenputtel", "cenerentola", "biancaneve", "sirenita", "sirenetta",
        "película", "dibujos", "animados", "capítulo", "épisode", "folge", "staffel", "temporada", "stagione",
    )

    private val foreignWords: Set<String> = ("""
        le la les du des une aux et sur dans avec sans contre chez vers sa ses leur cette qui est
        el los las del por para una y al su sus como
        der das und mit ein eine einen dem des ist von zum zur auf aus fur für
        il gli della dello delle nel nella che di da
        os uma com dos das ao pelo pela
        een voor och att bir ve ile
        rey roi re könig konig rei reina reine königin principe prince prinz princesa princesse prinzessin principessa
        bella bestia bête bete belle biest bestie
        navidad noël noel weihnachten natale feliz joyeux fröhliche frohe buon
        aventuras aventures abenteuer avventure aventura
        pelicula películas peliculas cuento cuentos conte contes märchen marchen fiaba
        nuevo nueva nouveau nouvelle neue neuen nuovo nuova
        amigos amis freunde amici amigo ami freund
        perro perros chien chiens hund hunde gato gatos chat chats katze gatto ratón raton souris maus topolino
        casa maison haus día dia jour noche nuit nacht notte mundo monde welt mondo
        sirène sirene meerjungfrau pequeña pequeño pequena pequeno petite petit kleine kleiner piccola piccolo
        siete sept sieben sette enanitos nains zwerge nani blanche neige
        dessins animés animes zeichentrick cartoni animati capitulo episodio
        grand gran große grosse grosso hermanos frères freres brüder fratelli niños ninos enfants kinder bambini
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() } + tellTale).toSet() -
        // words that are also ordinary English in titles
        setOf("die", "a", "on", "as", "son", "do", "no", "an", "in", "to", "is", "con", "pour", "den", "van", "het",
            "na", "al", "lo", "um", "de", "e", "o", "i")

    private const val ACCENTS = "éèêëàâçñãõßłąęśżźőűğşıœøåæáíóúôüöäìòùîï"
    private val englishWords = setOf("the", "of", "and", "with", "to", "in", "on", "for", "from", "my", "your", "is", "it",
        "at", "by", "his", "her", "new", "big", "little", "christmas", "adventure", "adventures", "movie", "episode",
        "show", "story", "stories", "friends", "day", "night", "world")

    private val wordRe = Regex("[\\p{L}]+")
    private val extRe = Regex("\\.(ia\\.)?[a-z0-9]{2,4}$", I)

    fun isEnglishTag(code: String): Boolean = code.trim().lowercase().startsWith("en")

    /** Why [fileName] looks non-English, or null. [itemLanguages] are the Archive item's language tags. */
    fun foreignReason(fileName: String, itemLanguages: List<String> = emptyList()): String? {
        val text = fileName.replace(extRe, "").replace('_', ' ').replace('.', ' ')

        // Another alphabet (Cyrillic, Japanese, Arabic, ...)
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            val script = Character.UnicodeScript.of(ch.code)
            if (script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON &&
                script != Character.UnicodeScript.INHERITED) {
                return "name is written in " + script.name.lowercase().replaceFirstChar { it.uppercase() } + " letters"
            }
        }
        if (englishAudio.containsMatchIn(text)) return null

        for (r in strong) r.find(text)?.let { return "name says “" + it.value.trim('(', ')', '[', ']', ' ') + "”" }

        val words = wordRe.findAll(text.lowercase()).map { it.value }.toList()
        words.firstOrNull { it in tellTale }?.let { return "name is in another language (“$it”)" }
        val foreign = words.filter { it in foreignWords }.distinct()
        val accents = text.lowercase().filter { it in ACCENTS }.toSet()
        val english = words.count { it in englishWords }
        if ((foreign.size >= 2 && foreign.size > english) || (foreign.isNotEmpty() && accents.isNotEmpty() && english == 0)) {
            return "name looks like another language (" + foreign.take(3).joinToString(", ") + ")"
        }

        val tags = itemLanguages.map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isNotEmpty() && tags.none { isEnglishTag(it) }) {
            return "Archive item is tagged " + tags.joinToString(", ") { languageName(it) }
        }
        return null
    }

    private val names = mapOf("fre" to "French", "fra" to "French", "spa" to "Spanish", "ger" to "German", "deu" to "German",
        "ita" to "Italian", "por" to "Portuguese", "pol" to "Polish", "rus" to "Russian", "jpn" to "Japanese",
        "chi" to "Chinese", "zho" to "Chinese", "kor" to "Korean", "dut" to "Dutch", "nld" to "Dutch", "swe" to "Swedish",
        "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "tur" to "Turkish", "hin" to "Hindi", "ara" to "Arabic",
        "heb" to "Hebrew", "gre" to "Greek", "ell" to "Greek", "hun" to "Hungarian", "cze" to "Czech", "ces" to "Czech",
        "tha" to "Thai", "vie" to "Vietnamese", "tgl" to "Tagalog")

    fun languageName(code: String): String = names[code.lowercase()] ?: code.replaceFirstChar { it.uppercase() }
}
