package com.nezumi_ai.data.export

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.skill.Skill
import com.nezumi_ai.data.skill.SkillRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * プリセットを .nzaip (JSON) へ書き出す。
 *
 * 含めないもの: MCP サーバー ID、メモリ設定、ロック／デフォルトフラグ、内部 ID。
 * スキルは「有効化されているもの」だけ（skillsEnabled かつ hidden でないもの）。
 * ユーザー定義スキルはファイル内容も埋め込み、別端末でも復元できるようにする。
 */
object PresetExportWriter {
    private const val TAG = "PresetExportWriter"
    const val FORMAT = "nezumi-preset-export"
    const val VERSION = 1
    const val EXTENSION = "nzaip"

    data class Result(
        val file: File,
        val skillCount: Int
    )

    fun exportToCacheFile(
        context: Context,
        preset: PresetEntity,
        skillRepository: SkillRepository = SkillRepository(context)
    ): Result {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "exportedAtIso",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        )

        val p = JSONObject()
        p.put("name", preset.name)
        p.put("icon", preset.icon)
        p.put("description", preset.description)
        p.put("systemPrompt", preset.systemPrompt)
        p.put("modelId", preset.modelId)
        p.put("toolCallingEnabled", preset.toolCallingEnabled)
        // enabledTools はそのまま JSON 配列文字列として保持（既存エンコードと互換）
        p.put("enabledTools", tryParseJsonArray(preset.enabledTools) ?: JSONArray())
        p.put("tagsCsv", preset.tagsCsv)
        // メモリ・MCP は意図的に含めない

        val enabledSkills = collectEnabledSkills(preset, skillRepository)
        p.put("skillsEnabled", preset.skillsEnabled && enabledSkills.isNotEmpty())
        val skillsArr = JSONArray()
        for (pack in enabledSkills) {
            skillsArr.put(pack)
        }
        p.put("skills", skillsArr)
        // 有効スキル名の一覧（インポート時の hidden 計算用）
        val namesArr = JSONArray()
        enabledSkills.forEach { namesArr.put(it.optString("name")) }
        p.put("enabledSkillNames", namesArr)

        root.put("preset", p)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = preset.name.ifBlank { "preset" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(40)
        val file = File(dir, "nezumi_${safe}_$stamp.$EXTENSION")
        file.writeText(root.toString(2), Charsets.UTF_8)
        Log.d(TAG, "Exported preset \"${preset.name}\" with ${enabledSkills.size} skill(s) -> ${file.absolutePath}")
        return Result(file = file, skillCount = enabledSkills.size)
    }

    private fun collectEnabledSkills(
        preset: PresetEntity,
        skillRepository: SkillRepository
    ): List<JSONObject> {
        if (!preset.skillsEnabled) return emptyList()
        val hidden = parseStringList(preset.hiddenSkillNames).toSet()
        val scan = skillRepository.scan()
        val out = mutableListOf<JSONObject>()
        for (skill in scan.skills) {
            if (skill.invalid) continue
            if (skill.name in hidden) continue
            out += skillToJson(skill, skillRepository)
        }
        return out
    }

    private fun skillToJson(skill: Skill, skillRepository: SkillRepository): JSONObject {
        val obj = JSONObject()
        obj.put("name", skill.name)
        obj.put("description", skill.description)
        obj.put("source", skill.source.name)
        // アセット由来は端末側に既にある想定でメタのみ。
        // ユーザー定義は本文を埋め込む。
        if (skill.source == Skill.Source.USER) {
            val filesArr = JSONArray()
            skillRepository.listUserSkillFiles(skill.name).getOrNull()?.forEach { entry ->
                if (entry.isDirectory) return@forEach
                val content = skillRepository.readUserFile(skill.name, entry.relativePath).getOrNull()
                    ?: return@forEach
                val f = JSONObject()
                f.put("path", entry.relativePath)
                f.put("content", content)
                filesArr.put(f)
            }
            // list が空でも SKILL.md を直接読むフォールバック
            if (filesArr.length() == 0) {
                skillRepository.read(skill.name).getOrNull()?.let { body ->
                    val f = JSONObject()
                    f.put("path", "SKILL.md")
                    // read() は front matter 除去後の本文を返すため、復元用に最小 front matter を付ける
                    f.put(
                        "content",
                        buildString {
                            append("---\n")
                            append("name: ").append(skill.name).append('\n')
                            append("description: \"")
                                .append(skill.description.replace("\"", "\\\""))
                                .append("\"\n")
                            append("---\n\n")
                            append(body)
                        }
                    )
                    filesArr.put(f)
                }
            }
            obj.put("files", filesArr)
        }
        return obj
    }

    private fun tryParseJsonArray(raw: String): JSONArray? = try {
        JSONArray(raw)
    } catch (_: Exception) {
        null
    }

    private fun parseStringList(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
