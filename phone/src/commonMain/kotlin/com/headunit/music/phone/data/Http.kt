package com.headunit.music.phone.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The platform's HTTP engine: NSURLSession on iPhone, OkHttp on desktop (CI tests). */
internal expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * One HTTP client for the whole app, so connections are reused between
 * searches and songs. Keeps cookies (YouTube's Mix pages need the ones it sets).
 */
internal object Http {
    val cookieStorage = AcceptAllCookiesStorage()

    val client: HttpClient by lazy {
        platformHttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 15_000
            }
            install(HttpCookies) { storage = cookieStorage }
            expectSuccess = false
        }
    }

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

/** True if [e] (or what caused it) is the network failing rather than a bad answer. */
internal fun isNetworkError(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        val name = t::class.simpleName.orEmpty()
        if (listOf("IOException", "Timeout", "Darwin", "Socket", "Connect", "UnknownHost", "UnresolvedAddress", "SSL")
                .any { name.contains(it) }
        ) return true
        t = t.cause
    }
    return false
}

// ---- Reading YouTube's JSON, which nests everything a dozen levels deep ----

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonElement?.int(key: String): Int? = str(key)?.toIntOrNull()
internal fun JsonElement?.long(key: String): Long? = str(key)?.toLongOrNull()

/** Follows [keys] down through objects. */
internal fun JsonElement?.path(vararg keys: String): JsonElement? {
    var e = this
    for (k in keys) e = (e as? JsonObject)?.get(k) ?: return null
    return e
}

/** YouTube's text: either {"simpleText": "..."} or {"runs": [{"text": "..."}, ...]}. */
internal fun JsonElement?.text(): String? {
    if (this == null) return null
    str("simpleText")?.let { return it }
    val runs = arr("runs") ?: return (this as? JsonObject)?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
    return runs.joinToString("") { it.str("text").orEmpty() }.takeIf { it.isNotEmpty() }
}

/** Every object stored under [key] anywhere inside this one (depth first, in document order). */
internal fun JsonElement.findAll(key: String, into: MutableList<JsonObject> = mutableListOf()): List<JsonObject> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonObject) into += v
            else v.findAll(key, into)
        }
        is JsonArray -> for (v in this) v.findAll(key, into)
        else -> {}
    }
    return into
}

/** The first string stored under [key] anywhere inside this one. */
internal fun JsonElement.findString(key: String, accept: (String) -> Boolean = { true }): String? {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonPrimitive && v.isString && accept(v.content)) return v.content
            v.findString(key, accept)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findString(key, accept)?.let { return it }
        else -> {}
    }
    return null
}

/** "&amp;" → "&" and friends (the Data API returns titles HTML-escaped). */
internal fun unescapeHtml(s: String): String {
    if ('&' !in s) return s
    return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val code = m.groupValues[1]
        when {
            code.startsWith("#x") -> code.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
            else -> when (code) {
                "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
                else -> m.value
            }
        }
    }
}

private fun codePoint(cp: Int): String =
    if (cp < 0x10000) cp.toChar().toString()
    else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/** Percent-encodes a query value (UTF-8). */
internal fun encodeQuery(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-_.~") append(ch)
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}
