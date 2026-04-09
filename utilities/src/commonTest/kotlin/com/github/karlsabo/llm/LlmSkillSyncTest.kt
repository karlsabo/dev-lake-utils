package com.github.karlsabo.llm

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
    ) {
        if (skills.isNotEmpty()) {
            val skillsDir = Path(sourceDir, ".agents", "skills")
            fs.createDirectories(skillsDir)
            for ((skillName, files) in skills) {
                val skillDir = Path(skillsDir, skillName)
                fs.createDirectories(skillDir)
                for ((fileName, content) in files) {
                    writeFile(Path(skillDir, fileName), content)
                }
            }
        }
        if (agentsContent != null) {
            writeFile(Path(sourceDir, "AGENTS.md"), agentsContent)
        }
    }

    @Test
    fun copiesSkillsToTargetDirectory() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("my-skill" to mapOf("prompt.md" to "skill content")),
                agentsContent = "guidelines"
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertEquals(listOf("my-skill"), result.skillsCopied)
            assertEquals("skill content", readFile(Path(homeDir, ".claude", "skills", "my-skill", "prompt.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun copiesGuidelinesToTargetFile() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(sourceDir, agentsContent = "# Guidelines\nDo the thing.")

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertTrue(result.guidelinesCopied)
            assertEquals("# Guidelines\nDo the thing.", readFile(Path(homeDir, ".claude", "CLAUDE.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun codexGuidelinesWrittenToInstructionsMd() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(sourceDir, agentsContent = "codex guidelines")

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CODEX)

            assertTrue(result.guidelinesCopied)
            assertEquals("codex guidelines", readFile(Path(homeDir, ".codex", "instructions.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun missingSkillsDirReturnsEmpty() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(sourceDir, agentsContent = "guidelines")

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertEquals(emptyList(), result.skillsCopied)
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun missingAgentsFileSkipsGuidelines() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(sourceDir, skills = mapOf("skill1" to mapOf("prompt.md" to "content")))

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertFalse(result.guidelinesCopied)
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun gooseTargetSkipsGuidelines() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("my-skill" to mapOf("prompt.md" to "content")),
                agentsContent = "guidelines"
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.GOOSE)

            assertFalse(result.guidelinesCopied)
            assertEquals(listOf("my-skill"), result.skillsCopied)
            assertEquals("content", readFile(Path(homeDir, ".config", "goose", "skills", "my-skill", "prompt.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun nestedSkillDirsCopiedRecursively() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            val skillsDir = Path(sourceDir, ".agents", "skills", "complex-skill")
            fs.createDirectories(skillsDir)
            writeFile(Path(skillsDir, "prompt.md"), "main prompt")
            val examplesDir = Path(skillsDir, "examples")
            fs.createDirectories(examplesDir)
            writeFile(Path(examplesDir, "example1.md"), "example one")
            setupSourceDir(sourceDir, agentsContent = "guidelines")

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertEquals(listOf("complex-skill"), result.skillsCopied)
            assertEquals("main prompt", readFile(Path(homeDir, ".claude", "skills", "complex-skill", "prompt.md")))
            assertEquals(
                "example one",
                readFile(Path(homeDir, ".claude", "skills", "complex-skill", "examples", "example1.md"))
            )
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun ignoresFilesInSkillsRoot() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("real-skill" to mapOf("prompt.md" to "content")),
                agentsContent = "guidelines"
            )
            writeFile(Path(sourceDir, ".agents", "skills", "README.md"), "not a skill")

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertEquals(listOf("real-skill"), result.skillsCopied)
            assertFalse(fs.exists(Path(homeDir, ".claude", "skills", "README.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun syncAllHitsEveryTarget() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("skill1" to mapOf("prompt.md" to "content")),
                agentsContent = "guidelines"
            )

            val results = sync.syncAll(sourceDir, homeDir)

            assertEquals(4, results.size)
            assertEquals(ToolTarget.CLAUDE, results[0].target)
            assertEquals(ToolTarget.CODEX, results[1].target)
            assertEquals(ToolTarget.GOOSE, results[2].target)
            assertEquals(ToolTarget.PI, results[3].target)

            results.forEach { assertEquals(listOf("skill1"), it.skillsCopied) }

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
    fun piGuidelinesWrittenToAgentsMd() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(sourceDir, agentsContent = "pi guidelines")

            val result = sync.sync(sourceDir, homeDir, ToolTarget.PI)

            assertTrue(result.guidelinesCopied)
            assertEquals("pi guidelines", readFile(Path(homeDir, ".pi", "agent", "AGENTS.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun piSkillsCopiedToAgentDir() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("my-skill" to mapOf("prompt.md" to "pi skill content")),
                agentsContent = "guidelines"
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.PI)

            assertEquals(listOf("my-skill"), result.skillsCopied)
            assertEquals("pi skill content", readFile(Path(homeDir, ".pi", "agent", "skills", "my-skill", "prompt.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun multipleSkillsAllCopied() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf(
                    "skill-a" to mapOf("prompt.md" to "a content"),
                    "skill-b" to mapOf("prompt.md" to "b content"),
                ),
                agentsContent = "guidelines",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertEquals(2, result.skillsCopied.size)
            assertTrue(result.skillsCopied.containsAll(listOf("skill-a", "skill-b")))
            assertEquals("a content", readFile(Path(homeDir, ".claude", "skills", "skill-a", "prompt.md")))
            assertEquals("b content", readFile(Path(homeDir, ".claude", "skills", "skill-b", "prompt.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun emptySourceReturnsEmptyResult() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        try {
            val result = sync.sync(sourceDir, homeDir, ToolTarget.CLAUDE)

            assertEquals(emptyList(), result.skillsCopied)
            assertFalse(result.guidelinesCopied)
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }
}
