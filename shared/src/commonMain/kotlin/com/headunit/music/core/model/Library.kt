// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.model

/** A collection of songs. [id] is [LIKED_ID], [HISTORY_ID] or a random id for ones you make. */
data class Library(
    val id: String,
    val name: String,
    val tracks: List<Track>,
    /** Video ids picked for the cover, by spot ("Change Cover Songs"); empty = the first songs. */
    val covers: List<String> = emptyList(),
) {
    /**
     * The songs whose artwork makes the tile's cover: one per spot, up to four.
     * Picked ones first (if still in the library), the rest filled from the
     * first songs added.
     */
    val coverTracks: List<Track>
        get() {
            val n = minOf(4, tracks.size)
            val picked = (0 until n).map { k -> covers.getOrNull(k)?.let { id -> tracks.firstOrNull { it.videoId == id } } }
            val used = picked.filterNotNull().mapTo(HashSet()) { it.videoId }
            val fill = tracks.filter { it.videoId !in used }.iterator()
            return picked.map { it ?: fill.next() }
        }
}

const val LIKED_ID = "liked"
const val HISTORY_ID = "history"
