package com.headunit.music.phone

// Desktop is only used for tests and screen previews on CI: nothing is kept between runs.

actual fun log(message: String) = println("MewZ: $message")

actual fun readClipboardText(): String? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
}.getOrNull()

actual fun appBundleId(): String = "com.headunit.music.phone"

actual fun builtInApiKey(): String = System.getenv("YOUTUBE_API_KEY").orEmpty()

actual object Settings {
    private val values = HashMap<String, String>()
    actual fun getString(key: String): String? = values[key]
    actual fun putString(key: String, value: String) { values[key] = value }
    actual fun remove(key: String) { values.remove(key) }
}
