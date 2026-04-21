package com.nezumi_ai.data.inference

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ツール実行結果をカード形式で保持するデータクラス。
 * メッセージに紐付けられ、JSON形式でDBに保存される。
 *
 * @param toolName 正規化済みツール名 (e.g. "setalarm", "starttimer")
 * @param success 実行成功フラグ
 * @param payload ツール固有の結果データ
 */
@Serializable
data class ToolResultCard(
    @SerialName("toolName")
    val toolName: String,
    
    @SerialName("success")
    val success: Boolean,
    
    @SerialName("payload")
    val payload: Map<String, JsonElement>
) {
    companion object {
        private const val TAG = "ToolResultCard"

        /**
         * JSON文字列からToolResultCard をデシリアライズ
         */
        fun fromJsonString(json: String): ToolResultCard? {
            return runCatching {
                Json.decodeFromString<ToolResultCard>(json)
            }.onFailure { e ->
                Log.w(TAG, "Failed to deserialize ToolResultCard from JSON: ${e.message}")
            }.getOrNull()
        }

        /**
         * JSON配列文字列から複数のToolResultCard をデシリアライズ
         */
        fun listFromJsonArray(jsonArray: String): List<ToolResultCard> {
            return runCatching {
                Json.decodeFromString<List<ToolResultCard>>(jsonArray)
            }.onFailure { e ->
                Log.w(TAG, "Failed to deserialize ToolResultCard list from JSON: ${e.message}")
            }.getOrDefault(emptyList())
        }

        /**
         * ToolResultCard から JSON文字列にシリアライズ
         */
        fun toJsonString(card: ToolResultCard): String {
            return runCatching {
                Json.encodeToString(card)
            }.onFailure { e ->
                Log.w(TAG, "Failed to serialize ToolResultCard to JSON: ${e.message}")
            }.getOrDefault("")
        }

        /**
         * 複数のToolResultCard から JSON配列文字列にシリアライズ
         */
        fun listToJsonArray(cards: List<ToolResultCard>): String {
            return runCatching {
                Json.encodeToString(cards)
            }.onFailure { e ->
                Log.w(TAG, "Failed to serialize ToolResultCard list to JSON: ${e.message}")
            }.getOrDefault("[]")
        }
    }

    /**
     * payload から指定キーの値を Int で取得
     */
    fun getPayloadInt(key: String): Int? {
        val el = payload[key] ?: return null
        val s = when (el) {
            is JsonObject -> el[key]?.contentStringOrNull()
            else -> el.contentStringOrNull()
        }
        return s?.toIntOrNull()
    }

    /**
     * payload から指定キーの値を String で取得
     */
    fun getPayloadString(key: String): String? {
        val el = payload[key] ?: return null
        return when (el) {
            is JsonObject -> el[key]?.contentStringOrNull()
            else -> el.contentStringOrNull()
        }
    }

    /**
     * payload から指定キーの値を Boolean で取得
     */
    fun getPayloadBoolean(key: String): Boolean? {
        val el = payload[key] ?: return null
        val s = when (el) {
            is JsonObject -> el[key]?.contentStringOrNull()
            else -> el.contentStringOrNull()
        } ?: return null
        return when {
            s.equals("true", ignoreCase = true) -> true
            s.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }

    private fun JsonElement.contentStringOrNull(): String? {
        return runCatching { this.jsonPrimitive.content }.getOrNull()
    }
}
