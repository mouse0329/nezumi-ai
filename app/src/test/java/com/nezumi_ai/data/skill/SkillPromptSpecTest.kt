package com.nezumi_ai.data.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPromptSpecTest {
    @Test
    fun catalog_includesOnlyMetadataAndLoadInstruction() {
        val catalog = SkillPromptSpec.catalog(listOf(Skill("pdf-reading", "Use this skill when reading PDFs.", Skill.Source.USER, "pdf-reading")))
        assertTrue(catalog.contains("pdf-reading: Use this skill when reading PDFs."))
        assertTrue(catalog.contains("get_skill"))
        assertFalse(catalog.contains("# Skill Title"))
    }

    @Test
    fun catalog_isEmptyWithoutSkills() {
        assertTrue(SkillPromptSpec.catalog(emptyList()).isEmpty())
    }
}
