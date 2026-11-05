package com.nezumi_ai.data.skill

/** Metadata exposed to a model; the Markdown body is loaded only through get_skill. */
data class Skill(
    val name: String,
    val description: String,
    val source: Source,
    val directoryName: String,
    val invalid: Boolean = false,
    val invalidReason: String? = null
) {
    enum class Source { ASSET, USER }
}

data class SkillScanResult(
    val skills: List<Skill>,
    val invalidSkills: List<String>
)
