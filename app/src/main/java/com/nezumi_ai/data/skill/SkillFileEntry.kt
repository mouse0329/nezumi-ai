package com.nezumi_ai.data.skill

/** A file or directory entry within a user skill folder. */
data class SkillFileEntry(
    val relativePath: String,
    val displayName: String,
    val isDirectory: Boolean
)
