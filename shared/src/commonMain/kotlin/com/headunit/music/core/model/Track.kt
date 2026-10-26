// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.model

/** One playable song, backed by a YouTube video. */
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    /** For the results list (cropped to 16:9 there). */
    val thumbnailUrl: String,
    /** Biggest picture for the square artwork on Now Playing. */
    val artworkUrl: String = thumbnailUrl,
    /**
     * True when [artworkUrl] is one of YouTube's 4:3 images with black bars baked
     * in above and below the picture; the square is cut from between them.
     */
    val artworkLetterboxed: Boolean = false,
    /** Null until we know it (durations come from a second API call). */
    val durationSeconds: Int? = null,
) {
    val durationText: String?
        get() = durationSeconds?.let { s ->
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            // (No String.format: it's JVM-only, and this file is shared with iOS.)
            fun two(n: Int) = n.toString().padStart(2, '0')
            if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
        }
}
