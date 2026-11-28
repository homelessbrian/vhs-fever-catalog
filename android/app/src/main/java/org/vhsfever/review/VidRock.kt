package org.vhsfever.review

data class VidRockPageResult(val message: String, val mediaUrls: List<String>)

object VidRock {
    private val directMedia = Regex("https?[^\\s<>\\\"]+\\.(m3u8|mp4)(\\?[^\\s<>\\\"]*)?", RegexOption.IGNORE_CASE)

    fun inspect(imdbId: String): VidRockPageResult {
        val id = imdbId.trim()
        require(Regex("tt\\d{5,10}").matches(id)) { "Enter a valid IMDb ID (for example tt0096895)." }
        val html = Http.request("https://vidrock.to/movie/$id", headers = mapOf("Accept" to "text/html,application/xhtml+xml"))
        val urls = directMedia.findAll(html).map { it.value.replace("&amp;", "&") }.distinct().take(20).toList()
        return VidRockPageResult("VidRock page responded successfully (${html.length} characters).", urls)
    }
}