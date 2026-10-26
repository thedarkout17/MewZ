package com.headunit.music.core

import com.headunit.music.core.model.Track

/** A song for tests: just an id (also used as its title). */
fun song(id: String, artist: String = "Artist", seconds: Int? = 200) =
    Track(videoId = id, title = id, artist = artist, thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg", durationSeconds = seconds)

fun songs(vararg ids: String) = ids.map { song(it) }

val List<Track>.ids get() = map { it.videoId }
