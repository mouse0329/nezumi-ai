package com.nezumi_ai.data.inference

import com.nezumi_ai.data.inference.cloud.CloudLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ツール実行結果をカード形式で保持するデータクラス (commonMain 版)。
 * メッセージに紐付けられ、JSON形式でDBに保存される。
 * シリアライズ形式は app 側 ToolResultCard と完全互換。
 */
@Serializable
data class CloudToolResultCard(
    @SerialName("toolName")
    val toolName: String,

    @SerialName("success")
    val success: Boolean,

    @SerialName("payload")
    val payload: Map<String, JsonElement>
) {
    companion object {
        private const val TAG = "ToolResultCard"

        /** 複数のCloudToolResultCard から JSON配列文字列にシリアライズ */
        fun listToJsonArray(cards: List<CloudToolResultCard>): String {
            return runCatching {
                Json.encodeToString(cards)
            }.onFailure { e ->
                CloudLog.w(TAG, "Failed to serialize ToolResultCard list to JSON: ${e.message}")
            }.getOrDefault("[]")
        }
    }
}
