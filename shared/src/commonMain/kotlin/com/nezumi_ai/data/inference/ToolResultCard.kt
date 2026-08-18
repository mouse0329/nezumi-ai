package com.nezumi_ai.data.inference

import com.nezumi_ai.data.inference.cloud.CloudLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** ツール実行結果カード (commonMain 版)。app 側 ToolResultCard とシリアライズ互換。 */
@Serializable
data class CloudToolResultCard(
    @SerialName("toolName") val toolName: String,
    @SerialName("success") val success: Boolean,
    @SerialName("payload") val payload: Map<String, JsonElement>
) {
    companion object {
        private const val TAG = "ToolResultCard"
        fun listToJsonArray(cards: List<CloudToolResultCard>): String {
            return runCatching { Json.encodeToString(cards) }
                .onFailure { e -> CloudLog.w(TAG, "serialize failed: ${e.message}") }
                .getOrDefault("[]")
        }
    }
}
