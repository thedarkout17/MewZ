// Shared core: plain Kotlin, no Android. Keep it that way so it can move to a
// Kotlin Multiplatform module (Android + iOS) as-is.
package com.headunit.music.core.playback

/** Why a song's audio couldn't be found. */
class AudioUnavailableException(val reason: Reason, message: String?) : Exception(message) {
    enum class Reason { Unavailable, AgeRestricted, Offline, RateLimited, ExtractorOutdated }
}

/** Why a song couldn't be played, in words for the screen. */
enum class PlayerError(val message: String, val skippable: Boolean) {
    Unavailable("This song isn't available", skippable = true),
    AgeRestricted("This song is age-restricted on YouTube", skippable = true),
    Offline("No internet connection", skippable = false),
    RateLimited("YouTube is limiting requests; try again shortly", skippable = false),
    ExtractorOutdated("YouTube changed something; the app needs an update", skippable = false),
    Other("Couldn't play this song", skippable = true);

    companion object {
        fun from(reason: AudioUnavailableException.Reason) = when (reason) {
            AudioUnavailableException.Reason.Unavailable -> Unavailable
            AudioUnavailableException.Reason.AgeRestricted -> AgeRestricted
            AudioUnavailableException.Reason.Offline -> Offline
            AudioUnavailableException.Reason.RateLimited -> RateLimited
            AudioUnavailableException.Reason.ExtractorOutdated -> ExtractorOutdated
        }
    }
}
