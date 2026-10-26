package com.headunit.music.phone.data

import com.headunit.music.core.model.Track
import com.headunit.music.core.text.SongText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Turning the song entries in YouTube's pages (the Mix / playlist panel,
 * playlist pages, "up next" suggestions) into our Tracks, the same way the
 * headunit turns NewPipe's items into Tracks (YouTubeMix.toTrack).
 */

/** Hour-long compilations and "10 hours of..." uploads aren't songs. */
internal const val MAX_SONG_SECONDS = 15 * 60

/**
 * A song entry as a Track: works for playlistPanelVideoRenderer,
 * playlistVideoRenderer and compactVideoRenderer, which share their field
 * names. Null for live streams (no length) and anything longer than [maxSeconds].
 */
internal fun videoEntryToTrack(r: JsonObject, maxSeconds: Int = MAX_SONG_SECONDS): Track? {
    val id = r.str("videoId")
        ?: r.path("navigationEndpoint", "watchEndpoint").str("videoId")
        ?: return null
    if (!VIDEO_ID.matches(id)) return null
    val seconds = r.obj("lengthText").text()?.let(::clockToSeconds)
        ?: r.str("lengthSeconds")?.toIntOrNull()
        ?: return null // live
    if (seconds <= 0 || seconds > maxSeconds) return null

    val rawTitle = r.obj("title").text() ?: return null
    val channel = (r.obj("shortBylineText") ?: r.obj("longBylineText") ?: r.obj("ownerText")).text().orEmpty()
    val (splitTitle, splitArtist) = SongText.splitArtist(SongText.cleanTitle(rawTitle), SongText.cleanArtist(channel))
    val (title, artist) = SongText.moveFeaturedArtists(splitTitle, splitArtist)

    val (artUrl, w, h) = biggestThumbnail(r.obj("thumbnail"))
        ?: Triple("https://i.ytimg.com/vi/$id/hqdefault.jpg", 480, 360)
    return Track(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        artworkUrl = artUrl,
        artworkLetterboxed = isLetterboxed(w, h, artUrl),
        durationSeconds = seconds,
    )
}

/** The biggest picture in a {"thumbnails": [...]} object: (url, width, height). */
internal fun biggestThumbnail(thumbnail: JsonElement?): Triple<String, Int, Int>? {
    val list = thumbnail.arr("thumbnails") ?: return null
    val best = list.mapNotNull { it as? JsonObject }
        .maxByOrNull { (it.int("width") ?: 0).toLong() * (it.int("height") ?: 0) } ?: return null
    var url = best.str("url") ?: return null
    if (url.startsWith("//")) url = "https:$url"
    return Triple(url, best.int("width") ?: 0, best.int("height") ?: 0)
}

/** YouTube's 4:3 images (hqdefault, sddefault, ...) have black bars above and below the picture. */
internal fun isLetterboxed(width: Int, height: Int, url: String): Boolean {
    if (width > 0 && height > 0) return width * 3 == height * 4
    return !url.contains("maxresdefault") && !url.contains("mqdefault") && !url.contains("hq720")
}

/** "3:45" → 225, "1:02:03" → 3723. */
internal fun clockToSeconds(clock: String): Int? {
    val parts = clock.trim().split(':').map { it.toIntOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0) { acc, p -> acc * 60 + p }
}

private val VIDEO_ID = Regex("""[\w-]{11}""")
