package com.headunit.music.phone.data

import com.headunit.music.core.sources.SuggestionSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * YouTube's own search suggestions, from Google's public suggest service (the
 * same list youtube.com shows as you type). No key or quota. If it can't be
 * reached there are simply no suggestions.
 */
class YouTubeSuggestions : SuggestionSource {
    override suspend fun suggest(query: String, limit: Int): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return runCatching {
            val body = Http.client.get(
                "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&oe=utf-8&q=" + encodeQuery(q),
            ).bodyAsText()
            // ["query", ["suggestion 1", "suggestion 2", ...]]
            val list = (Http.json.parseToJsonElement(body) as JsonArray)[1] as JsonArray
            list.mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(limit)
        }.getOrDefault(emptyList())
    }
}
