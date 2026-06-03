package com.nezumi_ai.voicevox

import android.content.Context
import android.util.Log

/**
 * VOICEVOX マネージャー（スタブ版）。
 *
 * [VoicevoxFeatureFlag.ENABLED] が false の場合、
 * すべてのメソッドはダミー値を返すだけで実際の処理は行いません。
 * これにより libvoicevox_onnxruntime.so が存在しなくてもビルド・実行可能です。
 */
class VoicevoxManager(private val context: Context) {

    companion object {
        private const val TAG = "VoicevoxManager"
        const val VVM_BASE_URL = "https://raw.githubusercontent.com/VOICEVOX/voicevox_vvm/main/vvms"
        const val DEFAULT_STYLE_ID = 9

        val modelCatalog: List<VoiceModelCatalogEntry> = emptyList()
    }

    enum class VoiceModelCategory(val label: String) {
        TALK("トーク"),
        SONG("ソング"),
        NEMO("Nemo トーク")
    }

    data class VoiceModelCatalogEntry(
        val fileName: String,
        val category: VoiceModelCategory,
        val styles: List<VoiceStyle>
    ) {
        val url: String = "$VVM_BASE_URL/$fileName"
        val displayName: String = "$fileName / ${styles.distinctBy { it.speakerName }.joinToString("・") { it.speakerName }}"
        val shortDescription: String = styles.joinToString("、") { "${it.speakerName}/${it.styleName}(${it.styleId})" }
    }

    data class VoiceStyle(
        val speakerName: String,
        val styleName: String,
        val styleId: Int
    ) {
        val displayName: String = "$speakerName / $styleName ($styleId)"
    }

    fun getSelectedModelFileName(): String = "（VOICEVOX無効）"

    suspend fun downloadSelectedModel(entry: VoiceModelCatalogEntry): Boolean {
        Log.w(TAG, "VOICEVOX is disabled. downloadSelectedModel() skipped.")
        return false
    }

    suspend fun initialize(): Boolean {
        Log.i(TAG, "VOICEVOX is disabled. initialize() skipped.")
        return false
    }

    suspend fun synthesize(text: String): ByteArray? = null

    fun release() {}

    suspend fun getAvailableStyles(): List<VoiceStyle> = emptyList()

    fun getSavedStyleId(): Int = DEFAULT_STYLE_ID

    fun setSelectedStyleId(styleId: Int) {}
}
