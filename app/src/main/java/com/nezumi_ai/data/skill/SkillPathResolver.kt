package com.nezumi_ai.data.skill

import java.io.File

/** Centralizes all path normalization and containment checks for skill content. */
object SkillPathResolver {
    private val skillNamePattern = Regex("[a-z0-9-]{1,64}")

    fun isValidName(name: String): Boolean = skillNamePattern.matches(name)

    fun resolveUserSkillDir(filesDir: File, skillName: String): File? =
        resolveChild(File(filesDir, "skills"), skillName)

    fun resolveReference(skillDirectory: File, referencePath: String): File? {
        if (referencePath.isBlank()) return null
        return resolveChild(File(skillDirectory, "references"), referencePath)
    }

    fun resolveChild(root: File, child: String): File? = runCatching {
        if (child.isBlank() || File(child).isAbsolute) return null
        val canonicalRoot = root.canonicalFile
        val candidate = File(root, child).canonicalFile
        candidate.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }.getOrNull()
}
