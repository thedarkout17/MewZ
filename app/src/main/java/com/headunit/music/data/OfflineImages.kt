package com.headunit.music.data

import coil.intercept.Interceptor
import coil.request.ImageResult

/**
 * Pictures for liked (downloaded) songs come from the saved copy instead of
 * YouTube: they show offline, and cost no data online either. Every other
 * picture loads as usual.
 */
class OfflineImages(private val downloads: Downloads) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed(request)
        val saved = downloads.localImage(url) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().data(saved).build())
    }
}
