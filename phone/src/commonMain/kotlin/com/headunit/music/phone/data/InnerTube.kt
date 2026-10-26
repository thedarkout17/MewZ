package com.headunit.music.phone.data

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/**
 * YouTube's internal API ("InnerTube"), which the YouTube apps themselves use.
 * This is what NewPipeExtractor does on the headunit, rewritten in plain Kotlin
 * so it runs on the iPhone too. Three "clients" are used, each for what it's
 * good at:
 *
 *  - **VISIONOS** (the Apple Vision Pro app) for audio streams: its stream links
 *    play directly, with no JavaScript player to run and no "PO token" to
 *    prove we're a real app. NewPipe and yt-dlp both rely on it for exactly
 *    that reason (NewPipeExtractor dev and yt-dlp 2026.10.26).
 *  - **WEB** (youtube.com) for YouTube Mixes and album track lists ("next").
 *  - **WEB_REMIX** (music.youtube.com) for album search.
 *
 * When YouTube changes something, these constants are what usually need
 * updating; NewPipeExtractor's ClientsConstants.java has the current values.
 */
internal object InnerTube {

    // VISIONOS client: same values as NewPipeExtractor's ClientsConstants (Oct 2026).
    private const val VISIONOS_NAME = "VISIONOS"
    private const val VISIONOS_VERSION = "1.04"
    private const val VISIONOS_DEVICE = "RealityDevice17,1"
    private const val VISIONOS_OS = "26.6.0.23O770"
    private const val VISIONOS_UA_OS = "26_6_0"

    /** Sent with the player request and when fetching the audio itself. */
    const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/$VISIONOS_VERSION($VISIONOS_DEVICE; U; CPU visionOS $VISIONOS_UA_OS like Mac OS X; US)"

    private const val WEB_ID = "1"
    private const val WEB_VERSION = "2.20260805.01.00"
    private const val WEB_REMIX_ID = "67"
    private const val WEB_REMIX_VERSION = "1.20260804.16.00"

    private const val YOUTUBEI = "https://www.youtube.com/youtubei/v1/"
    private const val GAPIS = "https://youtubei.googleapis.com/youtubei/v1/"
    private const val MUSIC = "https://music.youtube.com/youtubei/v1/"
    private const val NO_PRETTY = "prettyPrint=false"

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    /** Thrown when YouTube answers with something we can't use. */
    class BadResponse(message: String) : Exception(message)

    // ---- Requests ----

    private var consentSet = false

    /** Accept YouTube's cookie consent (otherwise some regions get a consent page, and Mixes can be empty). */
    private suspend fun ensureConsent() {
        if (consentSet) return
        val consent = Cookie("SOCS", "CAISAiAD", domain = ".youtube.com", path = "/")
        Http.cookieStorage.addCookie(io.ktor.http.Url("https://www.youtube.com/"), consent)
        Http.cookieStorage.addCookie(io.ktor.http.Url("https://music.youtube.com/"), consent)
        consentSet = true
    }

    private suspend fun post(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        ensureConsent()
        val response = Http.client.post(url) {
            contentType(ContentType.Application.Json)
            headers.forEach { (k, v) -> header(k, v) }
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value == 404) throw BadResponse("Not found (404)")
        if (response.status.value == 429) throw RateLimited()
        if (text.length < 50) throw BadResponse("HTTP ${response.status.value}: response too short")
        return try {
            Http.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BadResponse("HTTP ${response.status.value}: not JSON (${text.take(80)})")
        }
    }

    class RateLimited : Exception("YouTube is rate-limiting this connection (HTTP 429)")

    private fun context(
        clientName: String,
        clientVersion: String,
        extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {},
    ) = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                extra()
                put("hl", "en")
                put("gl", "US")
                put("utcOffsetMinutes", 0)
            }
            putJsonObject("request") {
                putJsonArray("internalExperimentFlags") {}
                put("useSsl", true)
            }
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
    }

    private fun visionOsContext(visitorData: String?) = context(VISIONOS_NAME, VISIONOS_VERSION) {
        put("clientScreen", "WATCH")
        put("platform", "MOBILE")
        if (visitorData != null) put("visitorData", visitorData)
        put("deviceMake", "Apple")
        put("deviceModel", VISIONOS_DEVICE)
        put("osName", "visionOS")
        put("osVersion", VISIONOS_OS)
    }

    private fun webContext() = context("WEB", WEB_VERSION) {
        put("originalUrl", "https://www.youtube.com")
        put("platform", "DESKTOP")
    }

    private fun musicContext() = context("WEB_REMIX", WEB_REMIX_VERSION) {
        put("platform", "DESKTOP")
    }

    private val visionOsHeaders = mapOf(
        "User-Agent" to VISIONOS_USER_AGENT,
        "X-Goog-Api-Format-Version" to "2",
    )

    private val webHeaders = mapOf(
        "User-Agent" to WEB_UA,
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com",
        "X-YouTube-Client-Name" to WEB_ID,
        "X-YouTube-Client-Version" to WEB_VERSION,
    )

    private val musicHeaders = mapOf(
        "User-Agent" to WEB_UA,
        "Origin" to "https://music.youtube.com",
        "Referer" to "https://music.youtube.com",
        "X-YouTube-Client-Name" to WEB_REMIX_ID,
        "X-YouTube-Client-Version" to WEB_REMIX_VERSION,
    )

    private fun JsonObject.with(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            this@with.forEach { (k, v) -> put(k, v) }
            build()
        }

    // ---- Visitor id: YouTube wants one on every player request ----

    private var visitorData: String? = null

    private suspend fun visitorData(fresh: Boolean = false): String {
        if (!fresh) visitorData?.let { return it }
        val r = post(YOUTUBEI + "visitor_id?$NO_PRETTY", visionOsHeaders, visionOsContext(null))
        val v = r.path("responseContext").str("visitorData")
            ?: throw BadResponse("No visitorData in visitor_id response")
        visitorData = v
        return v
    }

    // ---- Player: a song's streams ----

    /**
     * The VISIONOS player response for [videoId], and the "cpn" to append to its
     * stream links. If YouTube rejects the visitor id, gets a fresh one and asks again.
     */
    suspend fun player(videoId: String): Pair<JsonObject, String> {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            val cpn = randomId(16)
            val body = visionOsContext(visitorData(fresh = attempt > 0)).with {
                put("videoId", videoId)
                put("cpn", cpn)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            val url = GAPIS + "player?$NO_PRETTY&t=${randomId(12)}&id=$videoId"
            val r = post(url, visionOsHeaders, body)
            val status = r.obj("playabilityStatus").str("status")
            // A valid answer is about the video we asked for (and OK, or a real reason it isn't).
            if (r.path("videoDetails").str("videoId") == videoId || (status != null && status != "OK")) {
                return r to cpn
            }
            lastError = BadResponse("VISIONOS player response is not valid")
        }
        throw lastError!!
    }

    // ---- Next: Mixes and playlists (WEB) ----

    /** The "watch next" page: with a [playlistId], its "playlist" panel has the songs. */
    suspend fun next(
        videoId: String?,
        playlistId: String?,
        playlistIndex: Int? = null,
        params: String? = null,
    ): JsonObject {
        val body = webContext().with {
            if (videoId != null) put("videoId", videoId)
            if (playlistId != null) put("playlistId", playlistId)
            if (playlistIndex != null) put("playlistIndex", playlistIndex)
            if (params != null) put("params", params)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return post(YOUTUBEI + "next?$NO_PRETTY", webHeaders, body)
    }

    /** A playlist's page (WEB "browse" of "VL" + id): the fallback for album track lists. */
    suspend fun browsePlaylist(playlistId: String): JsonObject {
        val body = webContext().with {
            put("browseId", "VL$playlistId")
            put("params", "wgYCCAA%3D") // include unavailable videos (same as NewPipe)
        }
        return post(YOUTUBEI + "browse?$NO_PRETTY", webHeaders, body)
    }

    // ---- YouTube Music search ----

    /** Search filter for albums only (same "params" NewPipe's music_albums search sends). */
    const val MUSIC_ALBUMS_PARAMS = "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"

    suspend fun musicSearch(query: String, params: String): JsonObject {
        val body = musicContext().with {
            put("query", query)
            put("params", params)
        }
        return post(MUSIC + "search?$NO_PRETTY", musicHeaders, body)
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** The random ids YouTube's apps send ("cpn" = content playback nonce, and "t"). */
    fun randomId(length: Int): String = buildString { repeat(length) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) } }
}
