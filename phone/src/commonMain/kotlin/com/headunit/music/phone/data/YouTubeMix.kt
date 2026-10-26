package com.headunit.music.phone.data

import com.headunit.music.core.model.Track
import com.headunit.music.core.sources.MixSource
import com.headunit.music.phone.log
import kotlinx.serialization.json.JsonObject

/**
 * "Up next" songs for a song, like YouTube's autoplay: the song's YouTube Mix
 * (the endless radio playlist YouTube makes for every video, id "RD" + video
 * id). If YouTube won't give a mix, falls back to the video's suggestions.
 * Same behaviour as the headunit's YouTubeMix (NewPipe), no API quota used.
 */
object YouTubeMix : MixSource {

    override suspend fun upNext(seed: Track): List<Track> {
        val items = runCatching { mix(seed.videoId) }
            .onFailure { log("Mix failed for ${seed.videoId}: ${it.message}") }
            .getOrNull().orEmpty()
            .ifEmpty {
                runCatching { related(seed.videoId) }
                    .onFailure { log("Related failed for ${seed.videoId}: ${it.message}") }
                    .getOrNull().orEmpty()
            }
        return items.filter { it.videoId != seed.videoId }.distinctBy { it.videoId }
    }

    private suspend fun mix(videoId: String): List<Track> {
        val playlistId = "RD$videoId"
        val first = panel(InnerTube.next(videoId, playlistId))
            ?: throw InnerTube.BadResponse("No mix playlist")
        val entries = first.songEntries().toMutableList()
        // A mix page is ~25 songs; take one more page so the queue has some depth.
        if (entries.size < 30) {
            runCatching {
                val last = entries.lastOrNull()?.path("navigationEndpoint", "watchEndpoint")
                if (last != null) {
                    val more = panel(
                        InnerTube.next(
                            videoId = last.str("videoId"),
                            playlistId = last.str("playlistId") ?: playlistId,
                            playlistIndex = last.int("index"),
                            params = last.str("params"),
                        ),
                    )
                    if (more != null) {
                        // Each page repeats the songs before where it starts.
                        val from = (more.int("currentIndex") ?: -1) + 1
                        entries += more.songEntries().drop(from)
                    }
                }
            }.onFailure { log("Mix page 2 failed: ${it.message}") }
        }
        return entries.mapNotNull { videoEntryToTrack(it) }
    }

    /** The video's own "up next" suggestions. */
    private suspend fun related(videoId: String): List<Track> =
        InnerTube.next(videoId, null).findAll("compactVideoRenderer").mapNotNull { videoEntryToTrack(it) }

    /** The playlist panel on a watch page (where a Mix's or playlist's songs are). */
    internal fun panel(page: JsonObject): JsonObject? =
        page.path("contents", "twoColumnWatchNextResults", "playlist", "playlist") as? JsonObject

    internal fun JsonObject.songEntries(): List<JsonObject> =
        arr("contents").orEmpty().mapNotNull { it.obj("playlistPanelVideoRenderer") }
}
