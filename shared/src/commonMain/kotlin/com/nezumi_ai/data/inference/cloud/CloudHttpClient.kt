package com.nezumi_ai.data.inference.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** Android/CIO・iOS/Darwin のプラットフォームエンジンを使う共通 Ktor HttpClient。 */
object CloudHttpClient {
    val instance: HttpClient by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = Long.MAX_VALUE
            }
            expectSuccess = false
        }
    }
}
