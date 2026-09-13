package com.nezumi_ai.data.inference.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * バグ修正 (#LiteRT-stream-binder-throttle):
 *
 * `IRemoteTokenCallback` は `oneway` AIDL インターフェースであり、各呼び出しは
 * Binder ドライバの「非同期 (oneway) トランザクション専用バッファ」を消費する。
 * このバッファは既定でかなり小さく、トークン (LiteRT-LM の場合は 1 文字単位で
 * Flow が emit される) ごとに個別の oneway トランザクションを送っていると、
 * 生成速度が一定を超えた時点でバッファが埋まり、それ以降のトークンが
 * (Kotlin Flow 側の trySendBlocking では検知・回避できないレイヤーで) 配送されなく
 * なる。生成プロセス自体は動き続けるため「推論は継続しているのに UI には
 * 何も届かず、生成完了時にまとめて反映される」症状になる。
 *
 * これは `callbackFlow` 内のチャネル backpressure ([trySendBlocking] で対処可能な層)
 * とは別の、Binder IPC そのものの制約であるため、対処は「個々のトークンを
 * 都度 Binder 越しに送らず、短い時間ウィンドウでまとめてから 1 回の
 * oneway トランザクションとして送る」しかない。
 *
 * [source] から届く文字列チャンクを [windowMillis] ごとにまとめ、
 * [onBatch] を通じて送出する。[InferenceStreamProtocol] の制御マーカーは
 * 単純な文字列プレフィックス方式なので、複数チャンクをそのまま連結しても
 * 受信側 (ChatViewModel) の `splitStreamChunks` で正しく分解できる。
 *
 * @param windowMillis バッチ化の時間ウィンドウ。短すぎると Binder 詰まりが再発し、
 *   長すぎると体感のストリーミング滑らかさが落ちる。30〜50ms 程度を想定。
 * @param onBatch 貯まったチャンクを連結した文字列を渡すコールバック
 *   (呼び出し元スレッドで直接 Binder oneway 呼び出しを行うことを想定)。
 */
suspend fun collectBatchedForBinder(
    source: Flow<String>,
    windowMillis: Long = 40L,
    onBatch: (String) -> Unit
) {
    val buffer = StringBuilder()
    val bufferLock = Mutex()
    val upstreamDone = AtomicBoolean(false)

    // 定期的にバッファを flush するループと、upstream 収集ループを並行実行する。
    // どちらも「バッファに何か溜まっていれば flush する」を担当し、
    // upstream が終わったら残りを最後に flush して抜ける。
    val flushIfNeeded: suspend () -> Unit = {
        val toSend = bufferLock.withLock {
            if (buffer.isEmpty()) {
                null
            } else {
                val s = buffer.toString()
                buffer.clear()
                s
            }
        }
        if (toSend != null) {
            onBatch(toSend)
        }
    }

    coroutineScope {
        val collectJob = launch {
            source.collect { chunk ->
                bufferLock.withLock { buffer.append(chunk) }
            }
            upstreamDone.set(true)
        }

        val flushJob = launch {
            while (!upstreamDone.get()) {
                delay(windowMillis)
                flushIfNeeded()
            }
        }

        try {
            collectJob.join()
            flushJob.join()
        } finally {
            // upstream が正常終了・例外・キャンセルのいずれで抜けても、
            // ウィンドウの端数で溜まったままの最後の分は必ず送る。
            withContext(NonCancellable) {
                flushIfNeeded()
            }
        }
    }
}
