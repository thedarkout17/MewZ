package com.headunit.music.data.youtube

import com.headunit.music.data.SuggestionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube's own search suggestions (what people actually search), from Google's
 * public suggest service, the same list youtube.com shows as you type. Free: no
 * API key or quota. If it can't be reached, there are simply no suggestions
 * (recent searches still show).
 */
class YouTubeSuggestions : SuggestionProvider {

    override suspend fun suggest(query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        runCatching {
            val url = URL(
                "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&oe=utf-8&q=" +
                    URLEncoder.encode(q, "UTF-8"),
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            try {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                // ["query", ["suggestion 1", "suggestion 2", ...]]
                val list = JSONArray(body).getJSONArray(1)
                List(list.length()) { list.getString(it) }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .take(limit)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}
