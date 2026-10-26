package com.headunit.music.data

import android.content.Context
import org.json.JSONArray

/**
 * Past searches, newest first, saved on the device so they survive restarts.
 * Searching something again moves it back to the top instead of duplicating it.
 */
class SearchHistory(context: Context, private val maxEntries: Int = 15) {

    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            // Anything past the limit (e.g. saved when it was higher) is dropped.
            List(minOf(arr.length(), maxEntries)) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** Records a search and returns the updated list. */
    fun add(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return load()
        val updated = (listOf(q) + load().filterNot { it.equals(q, ignoreCase = true) }).take(maxEntries)
        save(updated)
        return updated
    }

    /** Removes one entry and returns the updated list. */
    fun remove(query: String): List<String> {
        val updated = load().filterNot { it.equals(query, ignoreCase = true) }
        save(updated)
        return updated
    }

    /**
     * Past searches that fit what's being typed: ones that start with it first,
     * then ones with a word starting with it.
     */
    fun matching(history: List<String>, typed: String): List<String> {
        val t = typed.trim().lowercase()
        if (t.isEmpty()) return history
        val starts = history.filter { it.lowercase().startsWith(t) && !it.equals(typed.trim(), ignoreCase = true) }
        val words = history.filter { h ->
            h !in starts && !h.equals(typed.trim(), ignoreCase = true) &&
                h.lowercase().split(' ').any { it.startsWith(t) }
        }
        return starts + words
    }

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY, JSONArray(list).toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
    }
}
