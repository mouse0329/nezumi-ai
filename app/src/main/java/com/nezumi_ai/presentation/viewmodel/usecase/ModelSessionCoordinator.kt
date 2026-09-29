package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.inference.remote.RemoteEngineProcessDiedException

/**
 * クラスタ E (モデル/エンジン管理) のうち、純粋ロジック部分を切り出したコーディネータ。
 *
 * モデルロード失敗時のエラー分類は、UI 表示文言の決定とファイル削除可否の判断に使われる
 * 重要な分岐であり、ViewModel から独立してテスト可能にするためにここへ集約する。
 */
object ModelSessionCoordinator {

    private const val GENERIC_LLAMA_INIT_FAILURE = "invalid model file or insufficient memory"

    /**
     * ローカル .litertlm を「破損・欠落」とみなして削除してよいときだけ true。
     * [TF_LITE_AUX not found] など TFLite/NPU ランタイムのエラーはファイル破損ではない。
     */
    fun shouldDeleteLocalModelFileOnLoadError(errorMessage: String): Boolean {
        if (errorMessage.contains("TF_LITE", ignoreCase = true)) return false
        // llama.cpp / LiteRT の汎用初期化失敗文。実ファイル破損とは限らない。
        if (errorMessage.contains(GENERIC_LLAMA_INIT_FAILURE, ignoreCase = true)) return false
        if (errorMessage.contains("flash_attn", ignoreCase = true)) return false
        if (errorMessage.contains("flash attention", ignoreCase = true)) return false
        if (errorMessage.contains("architecture", ignoreCase = true)) return false
        return errorMessage.contains("Cannot read", ignoreCase = true) ||
            errorMessage.contains("not found", ignoreCase = true) ||
            errorMessage.contains("corrupt", ignoreCase = true) ||
            errorMessage.contains("invalid", ignoreCase = true)
    }

    /**
     * Bug fix(#redownload-prompt-on-process-death):
     *   [shouldDeleteLocalModelFileOnLoadError] はエラーメッセージの部分文字列だけで
     *   判定しており、子プロセス (:gguf / :litert) が OOM kill 等で切断された場合の
     *   [RemoteEngineProcessDiedException] や bind タイムアウトのメッセージが偶然
     *   "invalid" / "not found" 等を含むと、ファイルは何も壊れていないのに削除フローへ
     *   倒れてしまっていた。
     */
    fun shouldDeleteLocalModelFileOnLoadError(errorMessage: String, error: Throwable?): Boolean {
        if (isRemoteProcessDisconnectionError(error)) return false
        return shouldDeleteLocalModelFileOnLoadError(errorMessage)
    }

    private tailrec fun isRemoteProcessDisconnectionError(error: Throwable?, depth: Int = 0): Boolean {
        if (error == null || depth > 8) return false
        if (error is RemoteEngineProcessDiedException) return true
        return isRemoteProcessDisconnectionError(error.cause, depth + 1)
    }

    /**
     * 実際のメモリ不足だけ true。llama.cpp の汎用 init 失敗文に含まれる
     * "insufficient memory" や flash_attn 必須エラーは含めない。
     */
    fun isMemoryLoadFailure(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is RemoteEngineProcessDiedException && error.likelyOutOfMemory) return true
        if (error is OutOfMemoryError) return true
        val errorMsg = error.message?.lowercase().orEmpty()
        if (errorMsg.contains(GENERIC_LLAMA_INIT_FAILURE)) {
            return isMemoryLoadFailure(error.cause)
        }
        if (errorMsg.contains("flash_attn") ||
            errorMsg.contains("flash attention") ||
            errorMsg.contains("unsupported model architecture") ||
            errorMsg.contains("unknown model architecture") ||
            errorMsg.contains("unknown architecture")
        ) {
            return false
        }
        if (errorMsg.contains("out of memory") ||
            errorMsg.contains("failed to allocate memory") ||
            errorMsg.contains("memory allocation failed") ||
            errorMsg.contains("cannot allocate") ||
            errorMsg.contains("memory usage is too high") ||
            errorMsg.contains("memory pressure") ||
            errorMsg.contains("memory limit")
        ) {
            return true
        }
        return isMemoryLoadFailure(error.cause)
    }

    fun isModelLoadWarningMarker(error: Throwable?): Boolean {
        val errorMsg = error?.message ?: return false
        return errorMsg == "MEMORY_WARNING_SHOWN" || errorMsg == "CPU_COMPAT_WARNING_SHOWN"
    }

    fun isGgufEngineModel(engineModelName: String): Boolean =
        engineModelName.lowercase().endsWith(".gguf")
}
