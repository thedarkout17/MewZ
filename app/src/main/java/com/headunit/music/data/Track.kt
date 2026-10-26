package com.headunit.music.data

/** One playable song, backed by a YouTube video. */
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    /** 480x360 (4:3, black bars baked in) for the results list, cropped to 16:9 there. */
    val thumbnailUrl: String,
    /**
     * 1280x720 (16:9, no bars) for the square artwork. Only HD uploads have it;
     * when it's missing the artwork uses [thumbnailUrl] instead.
     */
    val artworkUrl: String = thumbnailUrl,
    /** Null until we know it (durations come from a second API call). */
    val durationSeconds: Int? = null,
) {
    val durationText: String?
        get() = durationSeconds?.let { s ->
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
}
