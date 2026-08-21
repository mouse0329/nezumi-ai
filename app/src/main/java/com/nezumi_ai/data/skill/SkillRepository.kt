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
        // User entries win over asset entries with the same name (including invalid user
        // entries: we want them visible in the file manager so the user can fix them).
        val merged = LinkedHashMap<String, Skill>()
        asset.forEach { merged[it.name] = it }
        user.forEach { merged[it.name] = it }
        val skills = merged.values.sortedWith(compareBy({ it.invalid }, { it.name }))
        return SkillScanResult(skills, invalid.distinct()).also {
            synchronized(cacheLock) { cachedResult = it }
        }
    }

    fun read(skillName: String, referencePath: String? = null): Result<String> {
        if (!SkillPathResolver.isValidName(skillName)) return Result.failure(IllegalArgumentException("invalid_skill_name"))
        val skill = scan().skills.firstOrNull { it.name == skillName && !it.invalid }
            ?: return Result.failure(NoSuchElementException("skill_not_found:$skillName"))
        return when (skill.source) {
            Skill.Source.USER -> readUser(skill, referencePath)
            Skill.Source.ASSET -> readAsset(skill, referencePath)
        }
    }

    fun listUserSkillFiles(skillName: String): Result<List<SkillFileEntry>> = runCatching {
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        require(directory.isDirectory) { "skill_not_found" }
        buildList { collectSkillFiles(directory, directory, this) }
    }

    fun readUserFile(skillName: String, relativePath: String): Result<String> = runCatching {
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        val file = SkillPathResolver.resolveWithinSkill(directory, relativePath) ?: error("invalid_file_path")
        require(file.isFile) { "skill_file_not_found" }
        file.readText()
    }

    fun writeUserFile(skillName: String, relativePath: String, content: String): Result<Unit> = runCatching {
        val normalized = relativePath.trim().trim('/')
        require(normalized.isNotEmpty()) { "invalid_file_path" }
        require(normalized.endsWith(".md", ignoreCase = true)) { "file_must_be_markdown" }
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        val file = SkillPathResolver.resolveWithinSkill(directory, normalized) ?: error("invalid_file_path")
        require(!file.isDirectory) { "path_is_directory" }
        file.parentFile?.mkdirs()
        file.writeText(content)
        invalidateCache()
    }

    fun createUserDirectory(skillName: String, relativePath: String): Result<Unit> = runCatching {
        val normalized = relativePath.trim().trim('/')
        require(normalized.isNotEmpty()) { "invalid_folder_path" }
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        val target = SkillPathResolver.resolveWithinSkill(directory, normalized) ?: error("invalid_folder_path")
        require(!target.isFile) { "path_is_file" }
        require(target.exists() || target.mkdirs()) { "folder_create_failed" }
        invalidateCache()
    }

    fun deleteUserFile(skillName: String, relativePath: String): Result<Unit> = runCatching {
        val normalized = relativePath.trim().trim('/')
        require(normalized != "SKILL.md") { "cannot_delete_skill_md" }
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        val target = SkillPathResolver.resolveWithinSkill(directory, normalized) ?: error("invalid_file_path")
        require(target.exists()) { "skill_file_not_found" }
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        require(ok) { "skill_file_delete_failed" }
        target.parentFile?.let { parent -> cleanupEmptyDirectories(parent, directory) }
        invalidateCache()
    }

    fun deleteUserSkill(skillName: String): Result<Unit> = runCatching {
        val directory = userSkillDirectory(skillName) ?: error("invalid_skill_path")
        require(directory.isDirectory) { "skill_not_found" }
        require(directory.deleteRecursively()) { "skill_delete_failed" }
        invalidateCache()
    }

    /**
     * Creates an empty user skill on disk with a SKILL.md scaffold. Description is
     * intentionally optional at this stage — the user edits it from the file manager.
     */
    fun createUserSkill(skillName: String): Result<Unit> = runCatching {
        require(SkillPathResolver.isValidName(skillName)) { "invalid_skill_name" }
        val directory = SkillPathResolver.resolveUserSkillDir(context.filesDir, skillName) ?: error("invalid_skill_path")
        require(!directory.exists()) { "skill_already_exists" }
        require(directory.mkdirs()) { "skill_directory_create_failed" }
        val scaffold = buildString {
            append("---\n")
            append("name: ").append(skillName).append('\n')
            append("description: \"Describe what this skill does in one English sentence.\"\n")
            append("---\n\n")
            append("# ").append(skillName).append("\n\n")
            append("Write instructions here.\n")
        }
        File(directory, "SKILL.md").writeText(scaffold)
        invalidateCache()
    }

    private fun scanUser(invalid: MutableList<String>): List<Skill> {
        val root = File(context.filesDir, "skills")
        return root.listFiles().orEmpty().filter { it.isDirectory }.map { directory ->
            val text = directory.resolve("SKILL.md").takeIf { it.isFile }?.readText()
            parse(directory.name, Skill.Source.USER, directory.name, text, invalid)
        }
    }

    private fun scanAssets(invalid: MutableList<String>): List<Skill> {
        return context.assets.list("skills").orEmpty().mapNotNull { directory ->
            val content = runCatching { context.assets.open("skills/$directory/SKILL.md").bufferedReader().use { it.readText() } }.getOrNull()
            val parsed = parse(directory, Skill.Source.ASSET, directory, content, invalid)
            // Invalid built-in skills are not exposed at all — only user skills stay visible
            // so the user can repair them from the file manager.
            parsed.takeUnless { it.invalid }
        }
    }

    /**
     * Always returns a [Skill] (never null) — invalid entries are surfaced with
     * `invalid = true` so the UI can show them with a "使用不可" label rather than
     * silently deleting them from the list.
     */
    private fun parse(directory: String, source: Skill.Source, directoryName: String, text: String?, invalid: MutableList<String>): Skill {
        val reason = validate(directory, text)
        if (reason != null) {
            invalid += directory
            return Skill(
                name = directory,
                description = "",
                source = source,
                directoryName = directoryName,
                invalid = true,
                invalidReason = reason
            )
        }
        val frontMatter = frontMatter(text!!)!!
        val name = frontMatter["name"]!!.trim()
        val description = frontMatter["description"]!!.trim().trim('"', '\'')
        return Skill(name, description, source, directoryName)
    }

    private fun validate(directory: String, text: String?): String? {
        if (text == null) return "SKILL.md not found"
        val fm = frontMatter(text) ?: return "SKILL.md front matter missing"
        val name = fm["name"]?.trim()
        val description = fm["description"]?.trim()?.trim('"', '\'')
        if (name.isNullOrBlank()) return "front matter: name missing"
        if (!SkillPathResolver.isValidName(name)) return "front matter: name invalid"
        if (name != directory) return "front matter name must match folder name"
        if (description.isNullOrBlank()) return "front matter: description missing"
        if (description.length > 1024) return "description too long"
        if (!description.all { it.code in 32..126 || it == '\n' || it == '\t' }) return "description must be ASCII"
        return null
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
        val file = if (reference == null) File(directory, "SKILL.md")
        else SkillPathResolver.resolveWithinSkill(directory, reference) ?: error("invalid_reference_path")
        require(file.isFile) { "skill_file_not_found" }
        val content = file.readText()
        if (reference == null) content.substringAfter("\n---\n", content) else content
    }

    private fun readAsset(skill: Skill, reference: String?): Result<String> = runCatching {
        val relative = if (reference == null) "skills/${skill.directoryName}/SKILL.md" else {
            val safe = reference.trim().trim('/')
            require(safe.isNotEmpty() && !safe.contains("..")) { "invalid_reference_path" }
            "skills/${skill.directoryName}/$safe"
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

    private fun collectSkillFiles(current: File, skillRoot: File, out: MutableList<SkillFileEntry>) {
        current.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { file ->
                val relative = file.relativeTo(skillRoot).invariantSeparatorsPath
                if (file.isDirectory) {
                    out += SkillFileEntry("$relative/", "${file.name}/", isDirectory = true)
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
