package com.headunit.music.data

/** Source of search suggestions shown while typing. */
fun interface SuggestionProvider {
    suspend fun suggest(query: String, limit: Int): List<String>
}
