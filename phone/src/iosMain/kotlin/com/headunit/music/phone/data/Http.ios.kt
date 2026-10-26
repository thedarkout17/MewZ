package com.headunit.music.phone.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        config()
        engine {
            configureRequest { setAllowsCellularAccess(true) }
        }
    }
