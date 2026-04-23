package com.github.karlsabo.devlake.enghub

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmSkillSyncTest {
    private val fs = SystemFileSystem
    private val sync = LlmSkillSync()

    private fun createTempDir(): Path {
        val name = "llm-sync-test-${Random.nextLong().toULong().toString(16)}"
        val path = Path(SystemTemporaryDirectory, name)
        fs.createDirectories(path)
        return path
    }

    private fun writeFile(path: Path, content: String) {
        fs.sink(path).buffered().use { it.writeString(content) }
    }

    private fun readFile(path: Path): String =
        fs.source(path).buffered().readString()

    private fun deleteRecursively(path: Path) {
        if (!fs.exists(path)) return
        val meta = fs.metadataOrNull(path)
        if (meta?.isDirectory == true) {
            for (child in fs.list(path)) {
                deleteRecursively(child)
            }
        }
        fs.delete(path)
    }

    private fun setupSourceDir(
        sourceDir: Path,
        skills: Map<String, Map<String, String>> = emptyMap(),
        agentsContent: String? = null,
        notesContent: String? = null,
    ) {
        if (skills.isNotEmpty()) {
            val skillsDir = Path(sourceDir, ".agents", "skills")
            fs.createDirectories(skillsDir)
            for ((skillName, files) in skills) {
                val skillDir = Path(skillsDir, skillName)
                fs.createDirectories(skillDir)
                for ((fileName, content) in files) {
                    val filePath = Path(skillDir, *fileName.split("/").toTypedArray())
                    filePath.parent?.let { fs.createDirectories(it) }
                    writeFile(filePath, content)
                }
            }
        }
        if (agentsContent != null) {
            writeFile(Path(sourceDir, "AGENTS.md"), agentsContent)
        }
        if (notesContent != null) {
            writeFile(Path(sourceDir, "notes.md"), notesContent)
        }
    }

    @Test
    fun copiesSkillsToTargetDirectory() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("my-skill" to mapOf("prompt.md" to "skill content")),
                agentsContent = "guidelines",
                notesContent = "notes",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE, planningDir.toString())

            assertEquals(listOf("my-skill"), result.skillsCopied)
            assertEquals("skill content", readFile(Path(homeDir, ".claude", "skills", "my-skill", "prompt.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun copiesGuidelinesAndNotesToTargetFiles() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                agentsContent = "# Guidelines\nDo the thing.",
                notesContent = "# Notes\nRemember this.",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE, planningDir.toString())

            assertTrue(result.guidelinesCopied)
            assertTrue(result.notesCopied)
            assertEquals("# Guidelines\nDo the thing.", readFile(Path(homeDir, ".claude", "CLAUDE.md")))
            assertEquals("# Notes\nRemember this.", readFile(Path(homeDir, ".claude", "notes.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun replacesPlanningMarkdownPlaceholderInMarkdownFiles() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning", "docs")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf(
                    "templated" to mapOf(
                        "SKILL.md" to "Write to \${PLANNING_MARKDOWN_DIR}/story.md",
                        "references/output.md" to "Also use \${PLANNING_MARKDOWN_DIR}/review.md",
                        "script.txt" to "\${PLANNING_MARKDOWN_DIR} should stay untouched",
                    )
                ),
                agentsContent = "Guide path: \${PLANNING_MARKDOWN_DIR}/guide.md",
                notesContent = "Notes path: \${PLANNING_MARKDOWN_DIR}/notes.md",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CODEX, planningDir.toString())

            assertEquals(listOf("templated"), result.skillsCopied)
            assertEquals(
                "Write to ${planningDir}/story.md",
                readFile(Path(homeDir, ".codex", "skills", "templated", "SKILL.md"))
            )
            assertEquals(
                "Also use ${planningDir}/review.md",
                readFile(Path(homeDir, ".codex", "skills", "templated", "references", "output.md"))
            )
            assertEquals(
                "\${PLANNING_MARKDOWN_DIR} should stay untouched",
                readFile(Path(homeDir, ".codex", "skills", "templated", "script.txt"))
            )
            assertEquals(
                "Guide path: ${planningDir}/guide.md",
                readFile(Path(homeDir, ".codex", "instructions.md"))
            )
            assertEquals(
                "Notes path: ${planningDir}/notes.md",
                readFile(Path(homeDir, ".codex", "notes.md"))
            )
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun blankPlanningDirCopiesWithoutReplacement() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("templated" to mapOf("SKILL.md" to "Path: \${PLANNING_MARKDOWN_DIR}/story.md")),
                agentsContent = "guidelines",
                notesContent = "notes",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE, "   ")

            assertEquals(listOf("templated"), result.skillsCopied)
            assertEquals(
                "Path: \${PLANNING_MARKDOWN_DIR}/story.md",
                readFile(Path(homeDir, ".claude", "skills", "templated", "SKILL.md"))
            )
            assertFalse(fs.exists(Path(homeDir, "planning")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun relativePlanningDirFailsHard() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("templated" to mapOf("SKILL.md" to "content")),
                agentsContent = "guidelines",
                notesContent = "notes",
            )

            assertFailsWith<IllegalArgumentException> {
                sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE, "relative/path")
            }
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun createsPlanningDirAndParentDirectories() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "nested", "planning", "docs")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("templated" to mapOf("SKILL.md" to "content")),
                agentsContent = "guidelines",
                notesContent = "notes",
            )

            sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE, planningDir.toString())

            assertTrue(fs.exists(planningDir))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun gooseTargetSkipsGuidelinesButCopiesNotes() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("my-skill" to mapOf("prompt.md" to "content")),
                agentsContent = "guidelines",
                notesContent = "notes",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.GOOSE, planningDir.toString())

            assertFalse(result.guidelinesCopied)
            assertTrue(result.notesCopied)
            assertEquals("notes", readFile(Path(homeDir, ".config", "goose", "notes.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun syncAllHitsEveryTarget() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("skill1" to mapOf("prompt.md" to "content")),
                agentsContent = "guidelines",
                notesContent = "notes",
            )

            val results = sync.syncAll(sourceDir, homeDir, planningDir.toString())

            assertEquals(4, results.size)
            assertEquals(ToolTarget.CLAUDE, results[0].target)
            assertEquals(ToolTarget.CODEX, results[1].target)
            assertEquals(ToolTarget.GOOSE, results[2].target)
            assertEquals(ToolTarget.PI, results[3].target)

            results.forEach { result ->
                assertEquals(listOf("skill1"), result.skillsCopied)
                assertTrue(result.notesCopied)
            }

            assertTrue(results[0].guidelinesCopied)
            assertTrue(results[1].guidelinesCopied)
            assertFalse(results[2].guidelinesCopied)
            assertTrue(results[3].guidelinesCopied)
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun missingAgentsFileSkipsGuidelines() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("skill1" to mapOf("prompt.md" to "content")),
                notesContent = "notes",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE, planningDir.toString())

            assertFalse(result.guidelinesCopied)
            assertTrue(result.notesCopied)
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }
}
