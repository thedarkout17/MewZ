// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.text

/**
 * Turning YouTube's video titles and channel names into song titles and
 * artists: "The Weeknd - Starboy (Official Video) ft. Daft Punk" on
 * "TheWeekndVEVO" → "Starboy" by "The Weeknd ft. Daft Punk".
 */
object SongText {
    // "(Official Music Video)", "[Official Audio]", "(Lyrics)", "| Official Video" ...
    private val TITLE_NOISE = Regex(
        """\s*[(\[|]\s*(official\s*(music\s*)?(video|audio|visuali[sz]er|lyric\s*video)|lyrics?(\s*video)?|audio|hd|hq|4k|music\s*video|video\s*oficial)\s*[)\]]?""",
        RegexOption.IGNORE_CASE,
    )

    /** Strips YouTube clutter so titles read like song names. */
    fun cleanTitle(raw: String): String =
        raw.replace(TITLE_NOISE, "").trim().trimEnd('-', '|', '–').trim().ifEmpty { raw }

    /** "Taylor Swift - Topic" → "Taylor Swift", "TaylorSwiftVEVO" → "TaylorSwift". */
    fun cleanArtist(raw: String): String =
        raw.removeSuffix(" - Topic").removeSuffix("VEVO").removeSuffix("Vevo").trim().ifEmpty { raw }

    /**
     * Music uploads are usually titled "Artist - Song". When they are, show the
     * song as the title and the artist from the title (more accurate than the
     * channel name, which is often a label). Otherwise keep title + channel.
     */
    fun splitArtist(title: String, channel: String): Pair<String, String> {
        val parts = title.split(" - ", " – ", " — ", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[0].length <= 60) {
            return parts[1].trim() to parts[0].trim()
        }
        return title to channel
    }

    private val FEAT_BRACKETED = Regex("""\s*[(\[]\s*(?:ft|feat|featuring)\.?\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(?:ft|feat|featuring)\.?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val NAME_SEPARATORS = Regex("""\s+(?:ft|feat|featuring)\.?\s+|\s*,\s*|\s+&\s+""", RegexOption.IGNORE_CASE)

    /**
     * "Starboy ft. Daft Punk" by The Weeknd → "Starboy" by "The Weeknd ft. Daft Punk".
     * Handles "(feat. X)", "ft. X & Y", and uploads that repeat the credit twice.
     */
    fun moveFeaturedArtists(title: String, artist: String): Pair<String, String> {
        val credits = mutableListOf<String>()
        var t = FEAT_BRACKETED.replace(title) { credits += it.groupValues[1]; "" }
        FEAT_TAIL.find(t)?.let { m ->
            credits += m.groupValues[1]
            t = t.substring(0, m.range.first)
        }
        val names = credits
            .flatMap { it.split(NAME_SEPARATORS) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(artist, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        val cleanTitle = t.trim().ifEmpty { title }
        return if (names.isEmpty()) cleanTitle to artist
        else cleanTitle to "$artist ft. ${names.joinToString(", ")}"
    }

    /** "PT1H2M3S" → 3723. Null if it can't be read. */
    fun parseIsoDuration(iso: String): Int? {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso) ?: return null
        val (d, h, min, s) = m.destructured
        return (d.toIntOrNull() ?: 0) * 86400 + (h.toIntOrNull() ?: 0) * 3600 +
            (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }
}
