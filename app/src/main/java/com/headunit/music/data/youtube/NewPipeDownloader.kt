package com.headunit.music.data.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

/**
 * The HTTP client NewPipeExtractor uses to talk to YouTube.
 * One shared OkHttp client, so connections are reused between searches and songs.
 */
class NewPipeDownloader private constructor() : Downloader() {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                // YouTube is rate-limiting this connection and wants a captcha.
                throw ReCaptchaException("YouTube asked for a captcha", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    companion object {
        /** A normal desktop browser, which YouTube serves without fuss. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        val instance: NewPipeDownloader by lazy { NewPipeDownloader() }

        @Volatile private var initialized = false

        /** Call once before any search or playback (safe to call more than once). */
        fun init() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                NewPipe.init(instance, Localization("en", "US"), ContentCountry("US"))
                initialized = true
            }
        }
    }
}
