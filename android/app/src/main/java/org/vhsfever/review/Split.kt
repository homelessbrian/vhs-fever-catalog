package org.vhsfever.review

/**
 * Finds videos that are only one piece of something split into several files,
 * e.g. "101 Dalmatians (1992 VHS) - 03of20.mp4" or a DVD rip cut into VTS_01_1.VOB, VTS_01_2.VOB…
 *
 * Three signs:
 *  1. the catalog already joined the file into a multi-part recording ("Part 3 of 20");
 *  2. a stray piece whose name says "15of22", "Part 2 of 6" or "(2/6)" — only when there are
 *     4+ pieces or the file is shorter than 20 minutes, so a full-length "Part 1 of 3" episode stays;
 *  3. a DVD title cut into numbered .VOB chunks (VTS_03_1.VOB, VTS_03_2.VOB…).
 *
 * Sequel titles ("Back to the Future Part 2") and TV episodes (S04E94) are never flagged.
 * Returns a short human reason, or null for a whole video.
 */
object Split {
    private val I = RegexOption.IGNORE_CASE
    private val ofN = Regex("(?<![\\d.])(\\d{1,3})\\s*of\\s*(\\d{1,3})(?!\\d)", I)
    private val slashN = Regex("[(\\[]\\s*(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*[)\\]]")
    private val episodeCode = Regex("\\bS\\d{1,2}\\s*E\\d{1,3}", I)
    private val vob = Regex("(?:^|/)VTS_(\\d{2})_([1-9])\\.VOB$", I)
    private const val SHORT = 20 * 60

    /** "folder|VTS number" key for a DVD chunk, or null. */
    fun vobKey(f: FileItem): String? = vob.find(f.name)?.let { f.archiveItem + "|" + f.name.substringBeforeLast('/', "") + "|" + it.groupValues[1] }

    /** [vobChunks] = how many chunk files share this file's [vobKey]. */
    fun reason(f: FileItem, vobChunks: Int = 0): String? {
        f.partLabel?.let { return "$it of one video" }
        val name = f.name.substringAfterLast('/')
        if (!episodeCode.containsMatchIn(name)) {
            val m = ofN.find(name) ?: slashN.find(name)
            if (m != null) {
                val i = m.groupValues[1].toInt(); val n = m.groupValues[2].toInt()
                val short = f.durationSec != null && f.durationSec > 0 && f.durationSec < SHORT
                if (n >= 2 && i in 1..n && (n >= 4 || short)) return "Piece $i of $n of one video"
            }
        }
        if (vobChunks >= 2) vob.find(f.name)?.let { return "DVD rip cut into $vobChunks files (piece ${it.groupValues[2]})" }
        return null
    }

    /** File id -> reason, for every split piece in [files]. */
    fun find(files: List<FileItem>): Map<String, String> {
        val chunks = files.mapNotNull { f -> vobKey(f)?.let { it to f.name.substringAfterLast('/').lowercase() } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().size }
        val out = HashMap<String, String>()
        for (f in files) reason(f, vobKey(f)?.let { chunks[it] } ?: 0)?.let { out[f.id] = it }
        return out
    }
}
