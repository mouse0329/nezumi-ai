package com.nezumi_ai.data.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillPathResolverTest {
    @Test
    fun validName_acceptsOnlyKebabCaseWithinLimit() {
        assertTrue(SkillPathResolver.isValidName("pdf-reading-2"))
        assertFalse(SkillPathResolver.isValidName("PDF-reading"))
        assertFalse(SkillPathResolver.isValidName("has/slash"))
        assertFalse(SkillPathResolver.isValidName("a".repeat(65)))
    }

    @Test
    fun resolveChild_rejectsTraversalAndAbsolutePaths() {
        val root = File(System.getProperty("java.io.tmpdir"), "skill-resolver-test")
        assertNull(SkillPathResolver.resolveChild(root, "../secret.txt"))
        assertNull(SkillPathResolver.resolveChild(root, File(root.parentFile, "secret.txt").absolutePath))
        assertNotNull(SkillPathResolver.resolveChild(root, "references/guide.md"))
    }

    @Test
    fun resolveReference_isConfinedToReferencesDirectory() {
        val skill = File(System.getProperty("java.io.tmpdir"), "skill-resolver-test/example")
        assertNotNull(SkillPathResolver.resolveReference(skill, "guide.md"))
        assertNull(SkillPathResolver.resolveReference(skill, "../../SKILL.md"))
    }
}
