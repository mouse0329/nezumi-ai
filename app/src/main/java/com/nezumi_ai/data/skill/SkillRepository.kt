package com.nezumi_ai.data.skill

import android.content.Context
import java.io.File

/** Discovers built-in and user skills. User skills override built-in skills with the same name. */
class SkillRepository(private val context: Context) {
    fun scan(force: Boolean = false): SkillScanResult {
        synchronized(cacheLock) {
            if (!force) cachedResult?.let { return it }
        }
        val invalid = mutableListOf<String>()
        val asset = scanAssets(invalid)
        val user = scanUser(invalid)
        return SkillScanResult((asset + user).associateBy { it.name }.values.sortedBy { it.name }, invalid.distinct()).also {
            synchronized(cacheLock) { cachedResult = it }
        }
    }

    fun read(skillName: String, referencePath: String? = null): Result<String> {
        if (!SkillPathResolver.isValidName(skillName)) return Result.failure(IllegalArgumentException("invalid_skill_name"))
        val skill = scan().skills.firstOrNull { it.name == skillName }
            ?: return Result.failure(NoSuchElementException("skill_not_found:$skillName"))
        return when (skill.source) {
            Skill.Source.USER -> readUser(skill, referencePath)
            Skill.Source.ASSET -> readAsset(skill, referencePath)
        }
    }

    fun listUserSkillFiles(skillName: String): Result<List<SkillFileEntry>> = runCatching {
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        require(directory.isDirectory) { "skill_not_found" }
        buildList {
            if (File(directory, "SKILL.md").isFile) {
                add(SkillFileEntry("SKILL.md", "SKILL.md", isDirectory = false))
            }
            val referencesRoot = File(directory, "references")
            if (referencesRoot.isDirectory) {
                collectSkillFiles(referencesRoot, directory, this)
            }
        }
    }

    fun readUserFile(skillName: String, relativePath: String): Result<String> = runCatching {
        val file = resolveUserFile(userSkillDirectory(skillName) ?: error("invalid_skill_path"), relativePath)
            ?: error("invalid_file_path")
        require(file.isFile) { "skill_file_not_found" }
        file.readText()
    }

    fun writeUserFile(skillName: String, relativePath: String, content: String): Result<Unit> = runCatching {
        require(relativePath.endsWith(".md", ignoreCase = true)) { "file_must_be_markdown" }
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        val file = resolveUserFile(directory, relativePath) ?: error("invalid_file_path")
        file.parentFile?.mkdirs()
        file.writeText(content)
        invalidateCache()
    }

    fun deleteUserFile(skillName: String, relativePath: String): Result<Unit> = runCatching {
        require(relativePath != "SKILL.md") { "cannot_delete_skill_md" }
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        val file = resolveUserFile(directory, relativePath) ?: error("invalid_file_path")
        require(file.isFile) { "skill_file_not_found" }
        require(file.delete()) { "skill_file_delete_failed" }
        file.parent?.let { parent -> cleanupEmptyDirectories(File(parent), directory) }
        invalidateCache()
    }

    fun deleteUserSkill(skillName: String): Result<Unit> = runCatching {
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        require(directory.isDirectory) { "skill_not_found" }
        require(directory.deleteRecursively()) { "skill_delete_failed" }
        invalidateCache()
    }

    private fun scanUser(invalid: MutableList<String>): List<Skill> {
        val root = File(context.filesDir, "skills")
        return root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { directory ->
            parse(directory.name, Skill.Source.USER, directory.name, directory.resolve("SKILL.md").takeIf { it.isFile }?.readText(), invalid)
        }
    }

    private fun scanAssets(invalid: MutableList<String>): List<Skill> {
        return context.assets.list("skills").orEmpty().mapNotNull { directory ->
            val content = runCatching { context.assets.open("skills/$directory/SKILL.md").bufferedReader().use { it.readText() } }.getOrNull()
            parse(directory, Skill.Source.ASSET, directory, content, invalid)
        }
    }

    private fun parse(directory: String, source: Skill.Source, directoryName: String, text: String?, invalid: MutableList<String>): Skill? {
        val frontMatter = text?.let(::frontMatter)
        val name = frontMatter?.get("name")?.trim()
        val description = frontMatter?.get("description")?.trim()?.trim('"', '\'')
        val valid = name != null && description != null && SkillPathResolver.isValidName(name) && name == directory &&
            description.isNotBlank() && description.length <= 1024 && description.all { it.code in 32..126 || it == '\n' || it == '\t' }
        if (!valid) { invalid += name?.ifBlank { directory } ?: directory; return null }
        return Skill(name, description, source, directoryName)
    }

    private fun frontMatter(text: String): Map<String, String>? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        return text.substring(3, end).lineSequence().mapNotNull { line ->
            val index = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, index).trim() to line.substring(index + 1).trim()
        }.toMap()
    }

    private fun readUser(skill: Skill, reference: String?): Result<String> = runCatching {
        val directory = SkillPathResolver.resolveUserSkillDir(context.filesDir, skill.directoryName) ?: error("invalid_skill_path")
        val file = if (reference == null) File(directory, "SKILL.md") else SkillPathResolver.resolveReference(directory, reference) ?: error("invalid_reference_path")
        require(file.isFile) { "skill_file_not_found" }
        val content = file.readText()
        if (reference == null) content.substringAfter("\n---\n", content) else content
    }

    private fun readAsset(skill: Skill, reference: String?): Result<String> = runCatching {
        val relative = if (reference == null) "skills/${skill.directoryName}/SKILL.md" else {
            val root = File("/skills/${skill.directoryName}/references")
            val safe = SkillPathResolver.resolveReference(File("/skills/${skill.directoryName}"), reference)
                ?: error("invalid_reference_path")
            "skills/${skill.directoryName}/references/${safe.relativeTo(root).invariantSeparatorsPath}"
        }
        context.assets.open(relative).bufferedReader().use { reader ->
            val content = reader.readText()
            if (reference == null) content.substringAfter("\n---\n", content) else content
        }
    }

    private fun userSkillDirectory(skillName: String): File? {
        if (!SkillPathResolver.isValidName(skillName)) return null
        return SkillPathResolver.resolveUserSkillDir(context.filesDir, skillName)
    }

    private fun resolveUserFile(skillDirectory: File, relativePath: String): File? = when {
        relativePath == "SKILL.md" -> SkillPathResolver.resolveChild(skillDirectory, relativePath)
        relativePath.startsWith("references/") -> {
            val referenceRelative = relativePath.removePrefix("references/")
            if (referenceRelative.isBlank() || referenceRelative.endsWith("/")) return null
            SkillPathResolver.resolveReference(skillDirectory, referenceRelative)
        }
        else -> null
    }

    private fun collectSkillFiles(current: File, skillRoot: File, out: MutableList<SkillFileEntry>) {
        current.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { file ->
                val relative = file.relativeTo(skillRoot).invariantSeparatorsPath
                if (file.isDirectory) {
                    out += SkillFileEntry(relative, "${file.name}/", isDirectory = true)
                    collectSkillFiles(file, skillRoot, out)
                } else if (file.extension.equals("md", ignoreCase = true)) {
                    out += SkillFileEntry(relative, file.name, isDirectory = false)
                }
            }
    }

    private fun cleanupEmptyDirectories(directory: File, skillRoot: File) {
        var current = directory
        while (current.path.startsWith(skillRoot.path) && current != skillRoot) {
            if (!current.isDirectory || !current.list().isNullOrEmpty()) return
            if (!current.delete()) return
            current = current.parentFile ?: return
        }
    }

    private fun invalidateCache() {
        synchronized(cacheLock) { cachedResult = null }
    }

    companion object {
        private val cacheLock = Any()
        @Volatile private var cachedResult: SkillScanResult? = null
    }
}
