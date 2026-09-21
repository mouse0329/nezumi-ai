package com.nezumi_ai.presentation.viewmodel.usecase

/**
 * クラスタ E (モデル/エンジン管理) のうち、純粋ロジック部分を切り出したコーディネータ。
 *
 * モデルロード失敗時のエラー分類は、UI 表示文言の決定とファイル削除可否の判断に使われる
 * 重要な分岐であり、ViewModel から独立してテスト可能にするためにここへ集約する。
 * (ChatViewModel companion からの移管。実装は既存ロジックをそのまま保持)
 */
object ModelSessionCoordinator {

    /**
     * ローカル .litertlm を「破損・欠落」とみなして削除してよいときだけ true。
     * [TF_LITE_AUX not found] など TFLite/NPU ランタイムのエラーはファイル破損ではない。
     */
    fun shouldDeleteLocalModelFileOnLoadError(errorMessage: String): Boolean {
        if (errorMessage.contains("TF_LITE", ignoreCase = true)) return false
        return errorMessage.contains("Cannot read", ignoreCase = true) ||
            errorMessage.contains("not found", ignoreCase = true) ||
            errorMessage.contains("corrupt", ignoreCase = true) ||
            errorMessage.contains("invalid", ignoreCase = true)
    }

    /**
     * Bug fix(#redownload-prompt-on-process-death):
     *   [shouldDeleteLocalModelFileOnLoadError] はエラーメッセージの部分文字列だけで
     *   判定しており、子プロセス (:gguf / :litert) が OOM kill 等で切断された場合の
     *   [com.nezumi_ai.data.inference.remote.RemoteEngineProcessDiedException] や
     *   bind タイムアウトのメッセージが偶然 "invalid" / "not found" 等を含むと、
     *   ファイルは何も壊れていないのに「モデルファイルが読み込めません。
     *   再ダウンロードしてください」の削除フローに誤って倒れてしまっていた。
     *
     *   これは例外の「型」を見ずにメッセージの内容だけで判定していたのが原因のため、
     *   ここでは先に型で「プロセス切断由来かどうか」を判定し、そうであれば
     *   メッセージ内容に関わらずファイル破損とはみなさない (false を返す) ようにする。
     *
     * @param error 呼び出し元で保持している例外そのもの (message の抽出前)。
     *   null の場合はメッセージのみでの判定にフォールバックする。
     */
    fun shouldDeleteLocalModelFileOnLoadError(errorMessage: String, error: Throwable?): Boolean {
        if (isRemoteProcessDisconnectionError(error)) return false
        return shouldDeleteLocalModelFileOnLoadError(errorMessage)
    }

    /**
     * [error] またはその cause チェーンのどこかに
     * [com.nezumi_ai.data.inference.remote.RemoteEngineProcessDiedException] が
     * 含まれているか (＝モデルファイルではなく子プロセスの切断が原因か)。
     */
    private tailrec fun isRemoteProcessDisconnectionError(error: Throwable?, depth: Int = 0): Boolean {
        if (error == null || depth > 8) return false
        if (error is com.nezumi_ai.data.inference.remote.RemoteEngineProcessDiedException) return true
        return isRemoteProcessDisconnectionError(error.cause, depth + 1)
    }

    fun Throwable?.isMemoryLoadFailure(): Boolean {
        if (this == null) return false
        if (this is OutOfMemoryError) return true
        val errorMsg = message?.lowercase() ?: ""
        if (errorMsg.contains("llamainit failed") && errorMsg.contains("invalid model file or insufficient memory")) {
            return false
        }
        if (errorMsg.contains("out of memory") ||
            errorMsg.contains("failed to allocate memory") ||
            errorMsg.contains("memory allocation failed") ||
            errorMsg.contains("memory usage is too high") ||
            errorMsg.contains("memory pressure") ||
            errorMsg.contains("memory limit") ||
            errorMsg.contains("insufficient memory")
        ) {
            return true
        }
        return cause?.isMemoryLoadFailure() == true
    }

    fun Throwable?.isModelLoadWarningMarker(): Boolean {
        val errorMsg = this?.message ?: return false
        return errorMsg == "MEMORY_WARNING_SHOWN" || errorMsg == "CPU_COMPAT_WARNING_SHOWN"
    }

    fun isGgufEngineModel(engineModelName: String): Boolean =
        engineModelName.lowercase().endsWith(".gguf")
}
