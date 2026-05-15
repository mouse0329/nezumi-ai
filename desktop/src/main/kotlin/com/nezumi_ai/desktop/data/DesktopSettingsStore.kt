package com.nezumi_ai.desktop.data

import com.nezumi_ai.shared.settings.NezumiInferenceLimits
import com.nezumi_ai.shared.settings.NezumiSettingsFormState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * デスクトップ版の設定（共有フォーム + モデルパス・バックエンド）を
 * `~/.nezumi-ai/settings.json` に保存する。
 */
@Serializable
data class DesktopSettingsEnvelope(
    val form: NezumiSettingsFormState,
    val lastModelPath: String = "",
    val backendLabel: String = "CPU",
    val schemaVersion: Int = 1,
)

object DesktopSettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: DesktopSettingsEnvelope? = null

    @Volatile
    private var loadedFromDisk = false

    private fun appDir(): File {
        val dir = File(System.getProperty("user.home"), ".nezumi-ai")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun settingsFile(): File = File(appDir(), "settings.json")

    fun load(): DesktopSettingsEnvelope? {
        if (!loadedFromDisk) {
            synchronized(this) {
                if (!loadedFromDisk) {
                    cache = readDisk()
                    loadedFromDisk = true
                }
            }
        }
        return cache
    }

    fun save(envelope: DesktopSettingsEnvelope) {
        synchronized(this) {
            cache = envelope
            writeDisk(envelope)
        }
    }

    private fun readDisk(): DesktopSettingsEnvelope? {
        val f = settingsFile()
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            json.decodeFromString(DesktopSettingsEnvelope.serializer(), f.readText())
        }.getOrNull()
    }

    private fun writeDisk(envelope: DesktopSettingsEnvelope) {
        runCatching {
            val f = settingsFile()
            f.writeText(json.encodeToString(DesktopSettingsEnvelope.serializer(), envelope))
        }
    }

    /** 起動時に CPU 数が変わった場合の maxThreads 補正 */
    fun normalizeFormForRuntime(form: NezumiSettingsFormState): NezumiSettingsFormState {
        val mt = desktopMaxThreads()
        return form.copy(
            maxThreads = mt,
            llamaCppThreads = form.llamaCppThreads.coerceIn(NezumiInferenceLimits.MIN_THREADS, mt),
        )
    }

    fun desktopMaxThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 32)
}
