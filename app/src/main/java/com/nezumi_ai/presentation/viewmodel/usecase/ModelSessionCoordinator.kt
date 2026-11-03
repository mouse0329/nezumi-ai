package com.nezumi_ai.presentation.viewmodel.usecase

import android.content.Context
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.remote.RemoteEngineProcessDiedException
import com.nezumi_ai.data.preset.PresetConstants
import java.io.File

/**
 * クラスタ E (モデル/エンジン管理) のうち、純粋ロジック部分を切り出したコーディネータ。
 *
 * - ロード失敗時のエラー分類
 * - モデル ID / GGUF パスの正規化
 * - エンジン向けモデル名への変換
 *
 * ViewModel は State / オーバーレイ / Repository 呼び出しに専念し、判定と文字列変換はここに寄せる。
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

    /**
     * プリセットの modelId を設定系で使うモデルキーへ写す。
     * クラウド ID はそのまま。不明なビルトインは null（呼び出し側でフォールバック）。
     */
    fun mapPresetModelIdToSettingsModel(modelId: String): String? {
        if (CloudModelId.isCloud(modelId)) {
            return modelId
        }
        return when (modelId) {
            PresetConstants.MODEL_GEMMA4_LITERT -> "Gemma4-2B"
            PresetConstants.MODEL_QWEN3_GGUF -> null
            else -> modelId.takeIf { it.isNotBlank() }
        }
    }

    /**
     * GGUF モデル ID を実在する絶対パスに解決する。
     * - すでに絶対パスで存在すればそれを返す
     * - ファイル名だけ・相対・表記ゆれの場合は imported ディレクトリから同名を探す
     * 見つからなければ null。
     */
    fun resolveGgufModelPath(context: Context, model: String): String? {
        val trimmed = model.trim()
        if (!trimmed.lowercase().endsWith(".gguf")) return null
        val direct = File(trimmed)
        if (direct.isAbsolute && direct.isFile && direct.canRead()) {
            return direct.absolutePath
        }
        val name = direct.name
        if (name.isBlank()) return null
        val importedDir = File(context.filesDir, "models/imported")
        val candidates = importedDir.listFiles()?.filter { file ->
            file.isFile && file.name.equals(name, ignoreCase = true)
        }.orEmpty()
        return candidates.firstOrNull()?.absolutePath
    }

    /**
     * UI / 設定で使うモデル表記を正規化する。
     * クラウド ID と GGUF 絶対パスはそのまま（または imported 解決）。
     */
    fun normalizeModel(context: Context, model: String): String {
        val trimmed = model.trim()
        val lowered = trimmed.lowercase()
        // クラウドモデル ID (`cloud:...` / レガシー gemini_api / claude_api) はそのまま通す。
        // 以前は else 分岐で "Gemma4-2B" に潰されてしまい、プリセットでクラウドを選んでも
        // ローカル Gemma4-2B が動くバグの原因だった。
        if (CloudModelId.isCloud(trimmed)) {
            return trimmed
        }
        // GGUF は絶対パスが ID。表記ゆれやファイル名のみの場合でも imported から解決する。
        val resolvedGguf = resolveGgufModelPath(context, trimmed)
        if (resolvedGguf != null) return resolvedGguf

        val isLocalTaskPath =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm")) &&
                File(trimmed).isAbsolute

        return when {
            trimmed.equals("Gemma4-4B", ignoreCase = true) -> "Gemma4-4B"
            trimmed.equals("Gemma4-2B", ignoreCase = true) -> "Gemma4-2B"
            trimmed.equals("Gemma3n-4B", ignoreCase = true) -> "E4B"
            trimmed.equals("Gemma3n-2B", ignoreCase = true) -> "E2B"
            trimmed.equals("E4B", ignoreCase = true) -> "E4B"
            trimmed.equals("E2B", ignoreCase = true) -> "E2B"
            isLocalTaskPath -> trimmed
            else -> "Gemma4-2B"
        }
    }

    /**
     * ModelManager / エンジンに渡すモデル名へ変換する。
     * `cloud:...` は剥がさない。GGUF は絶対パスのまま。
     */
    fun toEngineModelName(context: Context, model: String): String {
        val normalized = normalizeModel(context, model)
        if (CloudModelId.isCloud(normalized)) {
            return normalized
        }
        if (normalized.lowercase().endsWith(".gguf") && File(normalized).isAbsolute) {
            return normalized
        }
        return when {
            normalized.equals("Gemma4-4B", ignoreCase = true) -> "gemma4-4b"
            normalized.equals("Gemma4-2B", ignoreCase = true) -> "gemma4-2b"
            normalized.equals("E4B", ignoreCase = true) -> "gemma-3n-4b"
            normalized.equals("E2B", ignoreCase = true) -> "gemma-3n-2b"
            (normalized.endsWith(".task") ||
                normalized.endsWith(".litertlm")) && File(normalized).isAbsolute -> normalized
            else -> "gemma4-2b"
        }
    }

    /** 既知ビルトインの概算サイズ。ファイルが存在すれば実サイズを優先。 */
    fun getEngineModelSizeBytes(engineModelName: String): Long? {
        val lowerName = engineModelName.lowercase()
        if (File(engineModelName).exists()) {
            return File(engineModelName).length()
        }
        return when (lowerName) {
            "gemma4-4b", "gemma-4-4b.litertlm" -> 4_800_000_000L
            "gemma4-2b", "gemma-4-2b.litertlm" -> 2_400_000_000L
            "gemma-3n-4b", "gemma-3n-4b.task" -> 4_000_000_000L
            "gemma-3n-2b", "gemma-3n-2b.task" -> 2_000_000_000L
            else -> null
        }
    }

    /** モデルエラーダイアログ本文の組み立て（純文字列）。 */
    fun formatModelErrorDialogMessage(
        title: String,
        message: String,
        details: String? = null
    ): String {
        return buildString {
            append(title)
            append("\n\n")
            append(message)
            details?.takeIf { it.isNotBlank() }?.let {
                append("\n\n")
                append(it)
            }
        }
    }
}
