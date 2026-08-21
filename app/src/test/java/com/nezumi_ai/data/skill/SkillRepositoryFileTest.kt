package com.nezumi_ai.data.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun listUserSkillFiles_returnsRootAndNestedEntries() {
        val skillDir = File(filesDir, "skills/demo-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: demo-skill\ndescription: \"Demo\"\n---\n\n# Demo\n")
        // A markdown file directly at the skill root (previously not allowed via UI).
        File(skillDir, "notes.md").writeText("root note")
        val nested = File(skillDir, "guides/advanced").apply { mkdirs() }
        File(nested, "guide.md").writeText("# Guide")

        val files = repository.listUserSkillFiles("demo-skill").getOrThrow()

        assertTrue(files.any { it.relativePath == "SKILL.md" && !it.isDirectory })
        assertTrue(files.any { it.relativePath == "notes.md" && !it.isDirectory })
        assertTrue(files.any { it.relativePath == "guides/" && it.isDirectory })
        assertTrue(files.any { it.relativePath == "guides/advanced/" && it.isDirectory })
        assertTrue(files.any { it.relativePath == "guides/advanced/guide.md" && !it.isDirectory })
    }

    @Test
    fun writeUserFile_acceptsRootLevelPath() {
        val skillDir = File(filesDir, "skills/root-file").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: root-file\ndescription: \"root\"\n---\n\n# root\n")

        repository.writeUserFile("root-file", "top.md", "hello").getOrThrow()

        assertEquals("hello", File(skillDir, "top.md").readText())
    }

    @Test
    fun writeUserFile_createsNestedDirectories() {
        val skillDir = File(filesDir, "skills/nested").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: nested\ndescription: \"n\"\n---\n\n# nested\n")

        repository.writeUserFile("nested", "a/b/c/note.md", "deep").getOrThrow()

        val file = File(skillDir, "a/b/c/note.md")
        assertTrue(file.isFile)
        assertEquals("deep", file.readText())
    }

    @Test
    fun createUserDirectory_supportsMultipleFilesPerFolder() {
        val skillDir = File(filesDir, "skills/many").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: many\ndescription: \"m\"\n---\n\n# many\n")

        repository.createUserDirectory("many", "docs").getOrThrow()
        repository.writeUserFile("many", "docs/one.md", "1").getOrThrow()
        repository.writeUserFile("many", "docs/two.md", "2").getOrThrow()

        val listed = repository.listUserSkillFiles("many").getOrThrow()
        assertTrue(listed.any { it.relativePath == "docs/one.md" })
        assertTrue(listed.any { it.relativePath == "docs/two.md" })
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
    fun deleteUserFile_removesFileButNotSkillMd() {
        val skillDir = File(filesDir, "skills/file-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: file-skill\ndescription: \"Files\"\n---\n\n# Files\n")
        val ref = File(skillDir, "notes/note.md").apply { parentFile?.mkdirs() }
        ref.writeText("note")

        repository.deleteUserFile("file-skill", "notes/note.md").getOrThrow()
        assertFalse(ref.exists())
        assertTrue(File(skillDir, "SKILL.md").exists())

        val cannotDelete = repository.deleteUserFile("file-skill", "SKILL.md")
        assertTrue(cannotDelete.isFailure)
    }

    @Test
    fun deleteUserFile_removesDirectoryRecursively() {
        val skillDir = File(filesDir, "skills/tree").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: tree\ndescription: \"t\"\n---\n\n# tree\n")
        File(skillDir, "docs/inner").mkdirs()
        File(skillDir, "docs/inner/x.md").writeText("x")

        repository.deleteUserFile("tree", "docs").getOrThrow()

        assertFalse(File(skillDir, "docs").exists())
    }

    @Test
    fun writeAndReadUserFile_roundTrip() {
        val skillDir = File(filesDir, "skills/write-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: write-skill\ndescription: \"Write\"\n---\n\n# Write\n")

        repository.writeUserFile("write-skill", "references/docs/readme.md", "# Readme").getOrThrow()
        val content = repository.readUserFile("write-skill", "references/docs/readme.md").getOrThrow()

        assertEquals("# Readme", content)
    }

    @Test
    fun createUserSkill_writesScaffoldSkillMd() {
        repository.createUserSkill("fresh-skill").getOrThrow()

        val md = File(filesDir, "skills/fresh-skill/SKILL.md")
        assertTrue(md.isFile)
        assertTrue(md.readText().contains("name: fresh-skill"))
        assertTrue(repository.scan(force = true).skills.any { it.name == "fresh-skill" && !it.invalid })
    }

    @Test
    fun scan_keepsInvalidUserSkillsWithReason() {
        val skillDir = File(filesDir, "skills/broken").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("no front matter")

        val result = repository.scan(force = true)

        val entry = result.skills.firstOrNull { it.name == "broken" }
        assertNotNull("invalid skill should still be listed", entry)
        assertTrue(entry!!.invalid)
        assertNotNull(entry.invalidReason)
    }

    @Test
    fun renameSkillFolder_recoversValidityWhenFrontMatterMatches() {
        // Previously, renaming a skill produced a mismatch that was hidden as an
        // "invalidated" toast. The user could not tell why the entry vanished. Now
        // the file manager keeps it visible with a reason so it can be repaired.
        val skillDir = File(filesDir, "skills/renamed").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: old-name\ndescription: \"x\"\n---\n\n# x\n")

        val invalid = repository.scan(force = true).skills.first { it.name == "renamed" }
        assertTrue(invalid.invalid)
        assertNotNull(invalid.invalidReason)

        // Fix the front matter through the file manager APIs.
        repository.writeUserFile("renamed", "SKILL.md", "---\nname: renamed\ndescription: \"x\"\n---\n\n# x\n").getOrThrow()

        val fixed = repository.scan(force = true).skills.first { it.name == "renamed" }
        assertFalse(fixed.invalid)
    }
}
