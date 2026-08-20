package com.nezumi_ai.data.skill

/** All model-facing skill prompt text is deliberately kept in one place. */
object SkillPromptSpec {
    const val TOOL_NAME = "get_skill"
    const val TOOL_DESCRIPTION = "Retrieve the content of an available skill. Pass skillName to get its SKILL.md body. Pass referencePath as well to get a file under that skill's references directory."
    const val CATALOG_HEADER = "Available skills:"

    fun catalog(skills: List<Skill>): String = buildString {
        if (skills.isEmpty()) return ""
        appendLine(CATALOG_HEADER)
        skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        append("Use get_skill when a skill is relevant before following its instructions.")
    }
}
