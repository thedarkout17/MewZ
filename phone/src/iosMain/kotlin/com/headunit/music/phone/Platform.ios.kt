package com.headunit.music.phone

import platform.Foundation.NSBundle
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults

actual fun log(message: String) {
    NSLog("MewZ: %@", message)
}

actual fun appBundleId(): String = NSBundle.mainBundle.bundleIdentifier ?: "com.headunit.music.phone"

actual fun builtInApiKey(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("YouTubeApiKey") as? String)
        ?.trim()
        // An unset build setting can arrive as the literal "$(YOUTUBE_API_KEY)".
        ?.takeIf { it.isNotEmpty() && !it.startsWith("$(") }
        .orEmpty()

actual object Settings {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String): String? = defaults.stringForKey("mewz.$key")

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = "mewz.$key")
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey("mewz.$key")
    }
}
