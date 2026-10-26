package com.headunit.music.phone

/** Writes to the device log (Xcode console / Console.app on the iPhone; filter by "MewZ"). */
expect fun log(message: String)

/** The app's bundle id (com.headunit.music.phone, or what a sideloading tool changed it to). */
expect fun appBundleId(): String

/** The YouTube Data API key built into the app (the YOUTUBE_API_KEY CI secret), or "". */
expect fun builtInApiKey(): String

/**
 * Small values saved on the device (UserDefaults on iPhone): libraries, search
 * history, the last song. Survives restarts and the 7-day re-signing.
 */
expect object Settings {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
