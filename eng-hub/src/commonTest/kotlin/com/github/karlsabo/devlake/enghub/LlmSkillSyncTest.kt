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
import kotlin.test.assertFails
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

    private fun readFile(path: Path): String = fs.source(path).buffered().use { it.readString() }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

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
        writeSkillFiles(sourceDir, skills)
        writeOptionalFile(Path(sourceDir, "AGENTS.md"), agentsContent)
        writeOptionalFile(Path(sourceDir, "notes.md"), notesContent)
    }

    private fun writeSkillFiles(sourceDir: Path, skills: Map<String, Map<String, String>>) {
        if (skills.isNotEmpty()) {
            val skillsDir = Path(sourceDir, ".agents", "skills")
            fs.createDirectories(skillsDir)
            skills.forEach { (skillName, files) -> writeSkillDirectory(skillsDir, skillName, files) }
        }
    }

    private fun writeSkillDirectory(
        skillsDir: Path,
        skillName: String,
        files: Map<String, String>,
    ) {
        val skillDir = Path(skillsDir, skillName)
        fs.createDirectories(skillDir)
        files.forEach { (fileName, content) -> writeSkillFile(skillDir, fileName, content) }
    }

    private fun writeSkillFile(
        skillDir: Path,
        fileName: String,
        content: String,
    ) {
        val filePath = Path(skillDir, *fileName.split("/").toTypedArray())
        filePath.parent?.let { fs.createDirectories(it) }
        writeFile(filePath, content)
    }

    private fun writeOptionalFile(path: Path, content: String?) {
        if (content != null) {
            writeFile(path, content)
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
    fun retiresWipAlertTriageOnlyAfterPublishingReplacementToSelectedTarget() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        val piSkillsDir = Path(homeDir, ".pi", "agent", "skills")
        val claudeSkillsDir = Path(homeDir, ".claude", "skills")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("eh-alert-triage" to mapOf("SKILL.md" to "published")),
            )
            writeSkillDirectory(piSkillsDir, "wip-eh-alert-triage", mapOf("SKILL.md" to "retired"))
            writeSkillDirectory(piSkillsDir, "destination-only", mapOf("SKILL.md" to "preserve me"))
            writeSkillDirectory(piSkillsDir, "wip-eh-alert-triage-backup", mapOf("SKILL.md" to "similar name"))
            writeSkillDirectory(claudeSkillsDir, "wip-eh-alert-triage", mapOf("SKILL.md" to "not selected"))

            sync.sync(sourceDir, homeDir, ToolTarget.PI, planningDir.toString())

            assertEquals("published", readFile(Path(piSkillsDir, "eh-alert-triage", "SKILL.md")))
            assertFalse(fs.exists(Path(piSkillsDir, "wip-eh-alert-triage")))
            assertEquals("preserve me", readFile(Path(piSkillsDir, "destination-only", "SKILL.md")))
            assertEquals("similar name", readFile(Path(piSkillsDir, "wip-eh-alert-triage-backup", "SKILL.md")))
            assertEquals("not selected", readFile(Path(claudeSkillsDir, "wip-eh-alert-triage", "SKILL.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun keepsWipAlertTriageWhenReplacementInstallationFails() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        val skillsDir = Path(homeDir, ".pi", "agent", "skills")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf("eh-alert-triage" to mapOf("SKILL.md" to "published")),
            )
            fs.createDirectories(skillsDir)
            writeFile(Path(skillsDir, "eh-alert-triage"), "blocks replacement directory")
            writeSkillDirectory(skillsDir, "wip-eh-alert-triage", mapOf("SKILL.md" to "keep me"))

            assertFails {
                sync.sync(sourceDir, homeDir, ToolTarget.PI, planningDir.toString())
            }

            assertEquals("keep me", readFile(Path(skillsDir, "wip-eh-alert-triage", "SKILL.md")))
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
                    ),
                ),
                agentsContent = "Guide path: \${PLANNING_MARKDOWN_DIR}/guide.md",
                notesContent = "Notes path: \${PLANNING_MARKDOWN_DIR}/notes.md",
            )

            val result = sync.sync(sourceDir, homeDir, ToolTarget.CODEX, planningDir.toString())

            assertEquals(listOf("templated"), result.skillsCopied)
            assertEquals(
                "Write to $planningDir/story.md",
                readFile(Path(homeDir, ".codex", "skills", "templated", "SKILL.md")),
            )
            assertEquals(
                "Also use $planningDir/review.md",
                readFile(Path(homeDir, ".codex", "skills", "templated", "references", "output.md")),
            )
            assertEquals(
                "\${PLANNING_MARKDOWN_DIR} should stay untouched",
                readFile(Path(homeDir, ".codex", "skills", "templated", "script.txt")),
            )
            assertEquals(
                "Guide path: $planningDir/guide.md",
                readFile(Path(homeDir, ".codex", "instructions.md")),
            )
            assertEquals(
                "Notes path: $planningDir/notes.md",
                readFile(Path(homeDir, ".codex", "notes.md")),
            )
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun installsDescriptiveAlertTriageLogPathWithConfiguredPlanningDirectory() {
        val sourceDir = Path("..", "llm")
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            sync.sync(sourceDir, homeDir, ToolTarget.PI, planningDir.toString())

            val installedSkill = readFile(
                Path(homeDir, ".pi", "agent", "skills", "eh-alert-triage", "SKILL.md"),
            )
            assertTrue(
                installedSkill.contains(
                    "Create a running log at `$planningDir/alert-triage/{descriptive-name}.md`",
                ),
            )
            assertTrue(
                installedSkill.contains(
                    "Choose `{descriptive-name}` as a short kebab-case alert or incident name, " +
                        "such as `checkout-api-latency-2025-04-10`.",
                ),
            )
        } finally {
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun installsConfiguredGuidanceInBundledAlertTriageSkill() {
        val sourceDir = Path("..", "llm")
        val sourceSkill = readFile(
            Path(sourceDir, ".agents", "skills", "eh-alert-triage", "SKILL.md"),
        ).normalizeLineEndings()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        val guidance = """
            - **PagerDuty**: inspect incidents.
            - **incident.io**: inspect timelines.
            - **Confluence**: inspect runbooks.
            - **Slack**: inspect incident channels.
            - **Chronosphere**: inspect metrics.
            - **Grafana**: inspect dashboards.
            - **Splunk**: inspect logs.
            - **Local code**: inspect repositories.
        """.trimIndent()
        try {
            assertTrue(sourceSkill.contains("name: eh-alert-triage"))
            assertTrue(sourceSkill.contains("\n\${ALERT_TRIAGE_WHERE_TO_LOOK}\n"))
            assertTrue(
                sourceSkill.contains(
                    "incident management, documentation, chat, observability, logs, and local repositories",
                ),
            )
            assertFalse(sourceSkill.lowercase().contains("klaviyo"))
            assertFalse(sourceSkill.contains("/Users/karl.sabo/"))

            sync.sync(
                sourceDir,
                homeDir,
                ToolTarget.PI,
                planningDir.toString(),
                mapOf("ALERT_TRIAGE_WHERE_TO_LOOK" to guidance),
            )

            val installedSkill = readFile(
                Path(homeDir, ".pi", "agent", "skills", "eh-alert-triage", "SKILL.md"),
            ).normalizeLineEndings()
            assertTrue(installedSkill.contains("## Where to look\n\n$guidance\n\n## Correlate evidence"))
            assertFalse(installedSkill.contains("\${ALERT_TRIAGE_WHERE_TO_LOOK}"))
        } finally {
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun installsConfiguredMarkdownTemplateValuesInAlertTriageSkill() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        val guidance = """
            - Incident management: inspect the active incident.
            - Observability: compare service health with the alert window.
        """.trimIndent()
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf(
                    "alert-triage" to mapOf(
                        "SKILL.md" to "## Where to look\n\n\${ALERT_TRIAGE_WHERE_TO_LOOK}",
                        "references/details.md" to "Guidance:\n\${ALERT_TRIAGE_WHERE_TO_LOOK}",
                        "script.txt" to "\${ALERT_TRIAGE_WHERE_TO_LOOK}",
                    ),
                ),
                agentsContent = "Shared guidance:\n\${ALERT_TRIAGE_WHERE_TO_LOOK}",
                notesContent = "Shared notes:\n\${ALERT_TRIAGE_WHERE_TO_LOOK}",
            )

            sync.sync(
                sourceDir,
                homeDir,
                ToolTarget.PI,
                planningDir.toString(),
                mapOf("ALERT_TRIAGE_WHERE_TO_LOOK" to guidance),
            )

            val installedSkillDir = Path(homeDir, ".pi", "agent", "skills", "alert-triage")
            assertEquals("## Where to look\n\n$guidance", readFile(Path(installedSkillDir, "SKILL.md")))
            assertEquals("Guidance:\n$guidance", readFile(Path(installedSkillDir, "references", "details.md")))
            assertEquals(
                "\${ALERT_TRIAGE_WHERE_TO_LOOK}",
                readFile(Path(installedSkillDir, "script.txt")),
            )
            assertEquals("Shared guidance:\n$guidance", readFile(Path(homeDir, ".pi", "agent", "AGENTS.md")))
            assertEquals("Shared notes:\n$guidance", readFile(Path(homeDir, ".pi", "agent", "notes.md")))
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun preservesShellVariablesThatAreNotKnownTemplateKeys() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf(
                    "shell-example" to mapOf(
                        "SKILL.md" to "Home: ${'$'}{HOME}\nRegion: ${'$'}{AWS_REGION}",
                    ),
                ),
            )

            sync.sync(sourceDir, homeDir, ToolTarget.PI, planningDir.toString())

            assertEquals(
                "Home: ${'$'}{HOME}\nRegion: ${'$'}{AWS_REGION}",
                readFile(Path(homeDir, ".pi", "agent", "skills", "shell-example", "SKILL.md")),
            )
        } finally {
            deleteRecursively(sourceDir)
            deleteRecursively(homeDir)
        }
    }

    @Test
    fun installsCorrectiveGuidanceForMissingOrBlankAlertTriageTemplateValue() {
        val correctiveGuidance = """
            > **ENG HUB SKILL CONFIGURATION REQUIRED**
            >
            > This skill is incomplete because the Eng Hub Settings value
            > `llmTemplateValues.ALERT_TRIAGE_WHERE_TO_LOOK` is missing or blank. Tell the user to configure
            > `ALERT_TRIAGE_WHERE_TO_LOOK` in Eng Hub Settings, then rerun `syncLlmFiles` to reinstall the
            > completed skill. Do not continue as though this guidance were available.
        """.trimIndent()
        val unusableTemplateValues = listOf(
            emptyMap(),
            mapOf("ALERT_TRIAGE_WHERE_TO_LOOK" to " \n\t"),
        )

        unusableTemplateValues.forEach { templateValues ->
            val sourceDir = createTempDir()
            val homeDir = createTempDir()
            val planningDir = Path(homeDir, "planning")
            try {
                setupSourceDir(
                    sourceDir,
                    skills = mapOf(
                        "alert-triage" to mapOf(
                            "SKILL.md" to """
                                ## Where to look

                                ${'$'}{ALERT_TRIAGE_WHERE_TO_LOOK}

                                Reminder: ${'$'}{ALERT_TRIAGE_WHERE_TO_LOOK}
                            """.trimIndent(),
                        ),
                    ),
                )

                sync.sync(
                    sourceDir,
                    homeDir,
                    ToolTarget.PI,
                    planningDir.toString(),
                    templateValues,
                )

                val installedSkill = readFile(
                    Path(homeDir, ".pi", "agent", "skills", "alert-triage", "SKILL.md"),
                )
                assertEquals(
                    "## Where to look\n\n$correctiveGuidance\n\nReminder: $correctiveGuidance",
                    installedSkill,
                )
                assertFalse(installedSkill.contains("\${ALERT_TRIAGE_WHERE_TO_LOOK}"))
            } finally {
                deleteRecursively(sourceDir)
                deleteRecursively(homeDir)
            }
        }
    }

    @Test
    fun configuredPlanningTemplateValueCannotOverrideDedicatedPlanningDir() {
        val sourceDir = createTempDir()
        val homeDir = createTempDir()
        val planningDir = Path(homeDir, "planning")
        try {
            setupSourceDir(
                sourceDir,
                skills = mapOf(
                    "templated" to mapOf("SKILL.md" to "Path: \${PLANNING_MARKDOWN_DIR}/story.md"),
                ),
            )

            sync.sync(
                sourceDir,
                homeDir,
                ToolTarget.CLAUDE,
                planningDir.toString(),
                mapOf("PLANNING_MARKDOWN_DIR" to "/untrusted/override"),
            )

            assertEquals(
                "Path: $planningDir/story.md",
                readFile(Path(homeDir, ".claude", "skills", "templated", "SKILL.md")),
            )
            assertTrue(fs.exists(planningDir))
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
                readFile(Path(homeDir, ".claude", "skills", "templated", "SKILL.md")),
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
