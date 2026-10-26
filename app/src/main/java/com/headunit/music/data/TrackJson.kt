package com.headunit.music.data

import com.headunit.music.core.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Saving songs as JSON (libraries, and the player's last queue). */
internal fun List<Track>.toJson(): JSONArray = JSONArray().also { arr ->
    forEach { t ->
        arr.put(
            JSONObject()
                .put("videoId", t.videoId)
                .put("title", t.title)
                .put("artist", t.artist)
                .put("thumbnailUrl", t.thumbnailUrl)
                .put("artworkUrl", t.artworkUrl)
                .put("artworkLetterboxed", t.artworkLetterboxed)
                .apply { t.durationSeconds?.let { put("durationSeconds", it) } }
        )
    }
}

internal fun JSONArray.toTracks(): List<Track> = List(length()) { i ->
    val o = getJSONObject(i)
    Track(
        videoId = o.getString("videoId"),
        title = o.getString("title"),
        artist = o.getString("artist"),
        thumbnailUrl = o.getString("thumbnailUrl"),
        artworkUrl = o.optString("artworkUrl", o.getString("thumbnailUrl")),
        artworkLetterboxed = o.optBoolean("artworkLetterboxed", false),
        durationSeconds = if (o.has("durationSeconds")) o.getInt("durationSeconds") else null,
    )
}
