package com.nezumi_ai.data.miniapp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Mini App Platform 仕様 v1.1 §11 Manifest の Kotlin 表現。
 *
 * manifest.json は署名対象であり、インストール時 ([MiniAppInstaller]) の
 * 検証以降は変更不可（§4 Package 不変性）。
 */
data class MiniAppManifest(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val entry: String,
    val permissions: List<String>
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("version", version)
        put("publisher", publisher)
        put("entry", entry)
        put("permissions", buildJsonArray { permissions.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        /** §11: id はリバースドメイン形式 (com.example.aiapp) を要求する。 */
        private val ID_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        /** 仕様で定義された Permission 名の既知集合（§12/§14/§31）。未知の権限は警告扱いで受理する。 */
        val KNOWN_PERMISSIONS: Set<String> = setOf(
            "ai", "ai.generate", "ai.loadModel",
            "tools.list", "tools.call", "tools.register",
            "models.list", "models.read", "models.install", "models.remove",
            "engines.list", "engines.config",
            "image.generate", "image.edit",
            "download", "storage", "files.read", "files.write", "files.pick",
            "device.info", "events", "miniapps.list", "miniapps.manage",
            "mcp.list", "camera", "microphone"
        )

        /**
         * manifest.json を検証しながらパースする。
         * @throws MiniAppException PACKAGE_INVALID — 必須項目の欠落・形式不正
         */
        fun parse(json: String): MiniAppManifest {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw MiniAppException("PACKAGE_INVALID", "manifest.json がJSONとして不正です: ${e.message}")
            }
            fun requiredString(key: String): String {
                val v = (obj[key] as? JsonPrimitive)?.content.orEmpty()
                if (v.isBlank()) {
                    throw MiniAppException("PACKAGE_INVALID", "manifest.json に必須項目 '$key' がありません")
                }
                return v
            }
            val id = requiredString("id")
            if (!ID_PATTERN.matches(id)) {
                throw MiniAppException(
                    "PACKAGE_INVALID",
                    "manifest.json の id '$id' はリバースドメイン形式である必要があります"
                )
            }
            val entry = requiredString("entry")
            if (entry.startsWith("/") || entry.split('/').any { it == ".." }) {
                throw MiniAppException("PACKAGE_INVALID", "manifest.json の entry が不正です: $entry")
            }
            val permissions = (obj["permissions"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
            return MiniAppManifest(
                id = id,
                name = requiredString("name"),
                version = requiredString("version"),
                publisher = (obj["publisher"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "Unknown" },
                entry = entry,
                permissions = permissions
            )
        }

        fun fromJson(json: String): MiniAppManifest = parse(json)
    }
}

/** 仕様 §34 に準拠したエラーコード付き例外。 */
class MiniAppException(
    val code: String,
    message: String,
    val details: Any? = null,
    cause: Throwable? = null
) : Exception(message, cause)
