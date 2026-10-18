package com.nezumi_ai.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.data.skill.SkillPathResolver
import com.nezumi_ai.data.skill.SkillRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * .nzaip (nezumi-preset-export) を読み込み、新規プリセットとして登録する。
 *
 * MCP・メモリはフォーマット上含まれない（あっても無視）。
 * 埋め込みユーザー技能は filesDir/skills へ書き出し、有効スキル以外は hidden にする。
 */
object PresetImportReader {
    private const val TAG = "PresetImportReader"
    private const val EXPECTED_FORMAT = PresetExportWriter.FORMAT

    data class Result(
        val presetId: String,
        val presetName: String,
        val skillInstalled: Int
    )

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        presetRepository: PresetRepository,
        skillRepository: SkillRepository = SkillRepository(context)
    ): Result {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("ファイルを読めませんでした")

        val root = JSONObject(text)
        val format = root.optString("format", "")
        if (format != EXPECTED_FORMAT) {
            throw IllegalArgumentException("対応していない形式です (format=$format)")
        }
        val pObj = root.optJSONObject("preset")
            ?: throw IllegalArgumentException("preset がありません")

        val name = pObj.optString("name", "").ifBlank { "インポートしたプリセット" }
        val icon = pObj.optString("icon", "")
        val description = pObj.optString("description", "")
        val systemPrompt = pObj.optString("systemPrompt", "")
        val modelId = pObj.optString("modelId", "")
        val toolCallingEnabled = pObj.optBoolean("toolCallingEnabled", false)
        val tagsCsv = pObj.optString("tagsCsv", "")
        val enabledToolsJson = when (val tools = pObj.opt("enabledTools")) {
            is JSONArray -> tools.toString()
            is String -> tools
            else -> "[]"
        }

        // スキル復元（ユーザー定義のみ書き込み）
        val skillsArr = pObj.optJSONArray("skills") ?: JSONArray()
        val enabledNames = mutableSetOf<String>()
        val namesFromField = pObj.optJSONArray("enabledSkillNames")
        if (namesFromField != null) {
            for (i in 0 until namesFromField.length()) {
                val n = namesFromField.optString(i, "").trim()
                if (n.isNotEmpty()) enabledNames += n
            }
        }
        var skillInstalled = 0
        for (i in 0 until skillsArr.length()) {
            val s = skillsArr.optJSONObject(i) ?: continue
            val skillName = s.optString("name", "").trim()
            if (skillName.isEmpty() || !SkillPathResolver.isValidName(skillName)) continue
            enabledNames += skillName
            val files = s.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            if (installUserSkillFiles(context, skillName, files)) {
                skillInstalled++
            }
        }
        skillRepository.scan(force = true)

        val skillsEnabled = pObj.optBoolean("skillsEnabled", enabledNames.isNotEmpty())
        // ローカルにあるがエクスポート側で有効でなかったスキルは hidden にする
        val hidden = if (skillsEnabled && enabledNames.isNotEmpty()) {
            skillRepository.scan().skills
                .filter { !it.invalid && it.name !in enabledNames }
                .map { it.name }
        } else {
            emptyList()
        }

        val now = System.currentTimeMillis()
        val entity = PresetEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            icon = icon,
            systemPrompt = systemPrompt,
            modelId = modelId,
            enabledTools = enabledToolsJson,
            createdAt = now,
            updatedAt = now,
            isDefault = false,
            // メモリ・MCP は意図的にデフォルトのまま（エクスポート対象外）
            memoryEnabled = true,
            description = description,
            isLocked = false,
            toolCallingEnabled = toolCallingEnabled,
            tagsCsv = tagsCsv,
            mcpServerIds = "[]",
            skillsEnabled = skillsEnabled,
            hiddenSkillNames = PresetRepository.encodeToolIds(hidden)
        )
        presetRepository.createPreset(entity)
        Log.d(TAG, "Imported preset \"$name\" id=${entity.id}, skillsInstalled=$skillInstalled")
        return Result(
            presetId = entity.id,
            presetName = name,
            skillInstalled = skillInstalled
        )
    }

    private fun installUserSkillFiles(
        context: Context,
        skillName: String,
        files: JSONArray
    ): Boolean {
        val dir = SkillPathResolver.resolveUserSkillDir(context.filesDir, skillName) ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        var wrote = false
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val path = f.optString("path", "").trim().trim('/')
            val content = f.optString("content", "")
            if (path.isEmpty() || content.isEmpty()) continue
            if (!path.endsWith(".md", ignoreCase = true)) continue
            val target = SkillPathResolver.resolveWithinSkill(dir, path) ?: continue
            target.parentFile?.mkdirs()
            target.writeText(content)
            wrote = true
        }
        return wrote
    }
}
