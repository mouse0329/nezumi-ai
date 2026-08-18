package com.nezumi_ai.data.inference.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** クラウド推論共通 Ktor HttpClient。タイムアウト方針は旧 OkHttp 実装を再現。 */
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
