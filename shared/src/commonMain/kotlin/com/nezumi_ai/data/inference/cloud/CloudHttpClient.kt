package com.nezumi_ai.data.inference.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * クラウド推論エンジン共通の Ktor HttpClient (commonMain)。
 *
 * ## タイムアウト方針 (旧 OkHttp 実装の再現)
 * - connect: 15 秒
 * - request / socket: 事実上無制限 (Long.MAX_VALUE)。SSE / NDJSON ストリームは
 *   応答が長時間ペンディングになるため、読み側タイムアウトは切っておかない。
 *
 * ## 単一インスタンス
 * HttpClient はスレッドセーフかつコネクションプール保持のため全プロバイダで共有する。
 */
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
