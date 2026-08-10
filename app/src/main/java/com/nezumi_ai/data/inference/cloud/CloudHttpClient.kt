package com.nezumi_ai.data.inference.cloud

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * クラウド推論エンジン共通の OkHttp クライアント。
 *
 * ## タイムアウト方針
 * - **connect**: 15 秒。名前解決 + TCP + TLS の合計上限。
 * - **read**: 0 秒 = 無制限。SSE / NDJSON ストリームは応答が長時間ペンディングになるため、
 *   read タイムアウトは切っておかないと途中で SocketTimeoutException が飛ぶ。
 * - **write**: 30 秒。画像 base64 込みでもリクエスト本文の送信は 30 秒以内に終わる想定。
 * - **call**: 0 秒 = 無制限。読み側と同じ理由。
 *
 * ## 単一インスタンス
 * OkHttpClient はスレッドセーフかつプール保持のため、
 * 全プロバイダで 1 インスタンスを共有する。
 */
object CloudHttpClient {

    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
