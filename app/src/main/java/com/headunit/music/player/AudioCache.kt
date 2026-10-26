@file:OptIn(UnstableApi::class)

package com.headunit.music.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * On-disk audio cache shared by playback and pre-buffering.
 *
 * - The next song's first few seconds are written here ahead of time, so
 *   skipping starts instantly.
 * - Everything played passes through it too, so replaying a recent song
 *   costs no data.
 *
 * Capped at 64 MB (about 15–20 songs at 128 kbps); the least recently played go first.
 */
object AudioCache {
    private const val MAX_BYTES = 64L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    /** One instance per folder is allowed, so everything shares this one. */
    fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "audio"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}
