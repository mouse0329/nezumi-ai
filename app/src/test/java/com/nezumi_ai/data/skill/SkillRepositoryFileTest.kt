package com.nezumi_ai.data.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SkillRepositoryFileTest {
    private lateinit var repository: SkillRepository
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        File(filesDir, "skills").deleteRecursively()
        repository = SkillRepository(context)
    }

    @Test
    fun listUserSkillFiles_includesSkillMdAndReferences() {
        val skillDir = File(filesDir, "skills/demo-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: demo-skill\ndescription: \"Demo\"\n---\n\n# Demo\n")
        val refDir = File(skillDir, "references/guides").apply { mkdirs() }
        File(refDir, "guide.md").writeText("# Guide")

        val files = repository.listUserSkillFiles("demo-skill").getOrThrow()

        assertTrue(files.any { it.relativePath == "SKILL.md" && !it.isDirectory })
        assertTrue(files.any { it.relativePath == "references/guides/" && it.isDirectory })
        assertTrue(files.any { it.relativePath == "references/guides/guide.md" && !it.isDirectory })
    }

    @Test
    fun deleteUserSkill_removesDirectory() {
        val skillDir = File(filesDir, "skills/to-delete").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: to-delete\ndescription: \"Delete me\"\n---\n\n# Delete\n")

        repository.deleteUserSkill("to-delete").getOrThrow()

        assertFalse(skillDir.exists())
        assertTrue(repository.scan(force = true).skills.none { it.name == "to-delete" })
    }

    @Test
    fun deleteUserFile_removesReferenceButNotSkillMd() {
        val skillDir = File(filesDir, "skills/file-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: file-skill\ndescription: \"Files\"\n---\n\n# Files\n")
        val ref = File(skillDir, "references/note.md").apply { parentFile?.mkdirs() }
        ref.writeText("note")

        repository.deleteUserFile("file-skill", "references/note.md").getOrThrow()
        assertFalse(ref.exists())
        assertTrue(File(skillDir, "SKILL.md").exists())

        val cannotDelete = repository.deleteUserFile("file-skill", "SKILL.md")
        assertTrue(cannotDelete.isFailure)
    }

    @Test
    fun writeAndReadUserFile_roundTrip() {
        val skillDir = File(filesDir, "skills/write-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: write-skill\ndescription: \"Write\"\n---\n\n# Write\n")

        repository.writeUserFile("write-skill", "references/docs/readme.md", "# Readme").getOrThrow()
        val content = repository.readUserFile("write-skill", "references/docs/readme.md").getOrThrow()

        assertEquals("# Readme", content)
    }
}
