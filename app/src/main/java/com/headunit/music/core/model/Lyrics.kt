// Shared core: plain Kotlin, no Android. Keep it that way so it can move to a
// Kotlin Multiplatform module (Android + iOS) as-is.
package com.headunit.music.core.model

/** One line of lyrics; [time] (seconds) is when it's sung, null for unsynced lyrics. */
data class LyricLine(val time: Float?, val text: String)

/** A song's lyrics. [synced] lyrics have a time on every line, so they can follow along. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** The line being sung at [seconds] (synced lyrics only), or -1 before the first. */
    fun lineAt(seconds: Float): Int {
        if (!synced) return -1
        var found = -1
        for (i in lines.indices) {
            val t = lines[i].time ?: continue
            if (t <= seconds + 0.15f) found = i else break
        }
        return found
    }
}

/** LRC, the timed-lyrics format: "[mm:ss.xx] words". */
object Lrc {
    /** "[01:23.45] Some line" (a line can carry several times) → timed lines, in order. */
    fun parse(lrc: String): List<LyricLine> {
        val stamp = Regex("""\[(\d+):(\d{1,2}(?:[.:]\d{1,3})?)]""")
        return lrc.lines().flatMap { raw ->
            val times = stamp.findAll(raw).map { m ->
                m.groupValues[1].toInt() * 60f + m.groupValues[2].replace(':', '.').toFloat()
            }.toList()
            val text = raw.replace(stamp, "").trim()
            times.map { LyricLine(it, text) }
        }.sortedBy { it.time }
    }
}
