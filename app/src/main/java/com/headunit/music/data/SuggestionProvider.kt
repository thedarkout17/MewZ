package com.headunit.music.data

/**
 * Source of search suggestions shown while typing.
 * Swap [LocalSuggestionProvider] for a YouTube-backed one later; the UI won't change.
 */
fun interface SuggestionProvider {
    suspend fun suggest(query: String, limit: Int): List<String>
}

/**
 * Offline stand-in: matches against a small catalogue of popular artists and songs,
 * then pads with YouTube-style completions ("<query> songs", "<query> live", ...).
 */
class LocalSuggestionProvider(
    private val catalogue: List<String> = DEFAULT_CATALOGUE,
) : SuggestionProvider {

    override suspend fun suggest(query: String, limit: Int): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // 1. Whole entry starts with the query, 2. any word in it does.
        val startsWith = catalogue.filter { it.lowercase().startsWith(q) }
        val wordMatch = catalogue.filter { entry ->
            entry !in startsWith &&
                entry.lowercase().split(' ', '-', '(').any { it.startsWith(q) }
        }
        val matches = (startsWith + wordMatch).map { it.lowercase() }

        val completions = COMPLETION_SUFFIXES.map { "$q $it" }
        return (matches + completions).distinct().take(limit)
    }

    companion object {
        private val COMPLETION_SUFFIXES = listOf("songs", "live", "remix", "playlist", "acoustic")

        val DEFAULT_CATALOGUE = listOf(
            "Taylor Swift", "Taylor Swift Cruel Summer", "Taylor Swift Anti-Hero",
            "The Weeknd", "The Weeknd Blinding Lights", "The Weeknd Save Your Tears",
            "Drake", "Drake One Dance", "Drake God's Plan",
            "Billie Eilish", "Billie Eilish Birds of a Feather", "Billie Eilish Lovely",
            "Sabrina Carpenter Espresso", "Sabrina Carpenter Please Please Please",
            "Kendrick Lamar", "Kendrick Lamar Not Like Us", "Kendrick Lamar Humble",
            "SZA", "SZA Kill Bill", "SZA Saturn",
            "Bad Bunny", "Bad Bunny Tití Me Preguntó",
            "Dua Lipa", "Dua Lipa Levitating", "Dua Lipa Houdini",
            "Ed Sheeran Shape of You", "Ed Sheeran Perfect",
            "Harry Styles As It Was", "Harry Styles Watermelon Sugar",
            "Post Malone", "Post Malone Circles", "Post Malone Sunflower",
            "Olivia Rodrigo", "Olivia Rodrigo Drivers License", "Olivia Rodrigo Vampire",
            "Arctic Monkeys Do I Wanna Know", "Arctic Monkeys 505",
            "Coldplay", "Coldplay Yellow", "Coldplay Viva La Vida",
            "Imagine Dragons Believer", "Imagine Dragons Thunder",
            "Travis Scott", "Travis Scott Sicko Mode",
            "Bruno Mars", "Bruno Mars Uptown Funk", "Bruno Mars Die With A Smile",
            "Lady Gaga", "Chappell Roan Good Luck Babe", "Chappell Roan Pink Pony Club",
            "Hozier Too Sweet", "Benson Boone Beautiful Things",
            "Queen Bohemian Rhapsody", "Michael Jackson Billie Jean",
            "Fleetwood Mac Dreams", "Eagles Hotel California",
            "Daft Punk Get Lucky", "Frank Ocean", "Frank Ocean Pink + White",
            "Tame Impala The Less I Know The Better", "Mac Miller",
            "Lofi hip hop radio", "Lofi beats to study to", "Road trip playlist",
            "Driving music", "Chill vibes playlist", "Workout music",
            "Top hits 2026", "Throwback hits", "90s hip hop", "80s rock",
        )
    }
}
