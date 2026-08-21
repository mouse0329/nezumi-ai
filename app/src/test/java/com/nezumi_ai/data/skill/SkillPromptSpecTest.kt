package com.nezumi_ai.data.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPromptSpecTest {
    @Test
    fun catalog_isEmptyWhenNoSkills() {
        assertEquals("", SkillPromptSpec.catalog(emptyList()))
    }

    @Test
    fun catalog_listsValidSkills() {
        val skills = listOf(
            Skill("alpha", "Alpha", Skill.Source.USER, "alpha"),
            Skill("beta", "Beta", Skill.Source.USER, "beta")
        )
        val text = SkillPromptSpec.catalog(skills)
        assertTrue(text.startsWith(SkillPromptSpec.CATALOG_HEADER))
        assertTrue(text.contains("- alpha: Alpha"))
        assertTrue(text.contains("- beta: Beta"))
    }

    @Test
    fun catalog_hidesInvalidSkills() {
        val skills = listOf(
            Skill("good", "Good", Skill.Source.USER, "good"),
            Skill("bad", "", Skill.Source.USER, "bad", invalid = true, invalidReason = "broken")
        )
        val text = SkillPromptSpec.catalog(skills)
        assertTrue(text.contains("- good: Good"))
        assertFalse(text.contains("bad"))
    }

    @Test
    fun catalog_isEmptyWhenAllInvalid() {
        val skills = listOf(
            Skill("bad", "", Skill.Source.USER, "bad", invalid = true, invalidReason = "broken")
        )
        assertEquals("", SkillPromptSpec.catalog(skills))
    }
}
