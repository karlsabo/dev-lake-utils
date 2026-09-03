package com.github.karlsabo.devlake.enghub

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

private val logger = KotlinLogging.logger {}
private const val PLANNING_MARKDOWN_DIR_NAME = "PLANNING_MARKDOWN_DIR"
private const val PLANNING_MARKDOWN_DIR_TOKEN = "\${$PLANNING_MARKDOWN_DIR_NAME}"
private const val PUBLISHED_ALERT_TRIAGE_SKILL = "eh-alert-triage"
private const val RETIRED_ALERT_TRIAGE_SKILL = "wip-eh-alert-triage"
private val markdownTemplateToken = Regex(
    "\\$\\{(${LLM_TEMPLATE_KEYS.joinToString("|") { Regex.escape(it) }})}",
)
private val windowsAbsolutePath = Regex("^[A-Za-z]:[\\\\/].*")

private fun missingTemplateValuePrompt(templateName: String): String = """
    > **ENG HUB SKILL CONFIGURATION REQUIRED**
    >
    > This skill is incomplete because the Eng Hub Settings value
    > `llmTemplateValues.$templateName` is missing or blank. Tell the user to configure
    > `$templateName` in Eng Hub Settings, then rerun `syncLlmFiles` to reinstall the
    > completed skill. Do not continue as though this guidance were available.
""".trimIndent()

/**
 * Each tool target defines where skills and shared markdown land.
 * [guidelinesFileName] is null when the tool has no global guidelines equivalent (e.g., Goose).
 */
enum class ToolTarget(
    val toolDir: String,
    val skillsSubdir: String,
    val guidelinesFileName: String?,
) {
    CLAUDE(".claude", "skills", "CLAUDE.md"),
    CODEX(".codex", "skills", "instructions.md"),
    GOOSE(".config/goose", "skills", null),
    PI(".pi/agent", "skills", "AGENTS.md"),
}

data class SyncResult(
    val target: ToolTarget,
    val skillsCopied: List<String>,
    val guidelinesCopied: Boolean,
    val notesCopied: Boolean,
)

/**
 * Syncs LLM skill files from a central source directory to the correct locations
 * for Claude Code, OpenAI Codex CLI, Block Goose CLI, and Pi.
 */
class LlmSkillSync(
    private val fileSystem: FileSystem = SystemFileSystem,
) {
    private val fileCopier = LlmFileCopier(fileSystem)

    /** Sync skills, shared notes, and guidelines to a single tool target. */
    fun sync(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        planningMarkdownDir: String,
        llmTemplateValues: Map<String, String> = emptyMap(),
    ): SyncResult {
        val replacements = prepareMarkdownReplacements(planningMarkdownDir, llmTemplateValues)
        return syncPrepared(sourceLlmDir, homeDir, target, replacements)
    }

    /** Sync to all tool targets. */
    fun syncAll(
        sourceLlmDir: Path,
        homeDir: Path,
        planningMarkdownDir: String,
        llmTemplateValues: Map<String, String> = emptyMap(),
        targets: List<ToolTarget> = ToolTarget.entries,
    ): List<SyncResult> {
        val replacements = prepareMarkdownReplacements(planningMarkdownDir, llmTemplateValues)
        return targets.map { syncPrepared(sourceLlmDir, homeDir, it, replacements) }
    }

    private fun syncPrepared(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacements: MarkdownReplacements,
    ): SyncResult {
        val skillsCopied = syncSkills(sourceLlmDir, homeDir, target, replacements)
        val guidelinesCopied = syncGuidelines(sourceLlmDir, homeDir, target, replacements)
        val notesCopied = syncNotes(sourceLlmDir, homeDir, target, replacements)
        return SyncResult(target, skillsCopied, guidelinesCopied, notesCopied)
    }

    private fun prepareMarkdownReplacements(
        planningMarkdownDir: String,
        llmTemplateValues: Map<String, String>,
    ): MarkdownReplacements {
        if (llmTemplateValues.containsKey(PLANNING_MARKDOWN_DIR_NAME)) {
            logger.warn {
                "$PLANNING_MARKDOWN_DIR_NAME is reserved; ignoring its llmTemplateValues entry"
            }
        }
        return MarkdownReplacements(
            planningMarkdownDir = preparePlanningMarkdownDir(planningMarkdownDir),
            templateValues = llmTemplateValues - PLANNING_MARKDOWN_DIR_NAME,
        )
    }

    private fun preparePlanningMarkdownDir(planningMarkdownDir: String): String? {
        val trimmed = planningMarkdownDir.trim()
        if (trimmed.isBlank()) {
            logger.error {
                "planningMarkdownDir is blank; copying markdown files " +
                    "without replacing $PLANNING_MARKDOWN_DIR_TOKEN"
            }
            return null
        }

        require(isAbsolutePath(trimmed)) {
            "planningMarkdownDir must be an absolute path, got: $trimmed"
        }

        Path(trimmed).create(fileSystem)
        return trimmed
    }

    private fun syncSkills(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacements: MarkdownReplacements,
    ): List<String> {
        val skillsSourceDir = Path(sourceLlmDir, ".agents", "skills")
        if (!fileSystem.exists(skillsSourceDir)) {
            logger.warn { "Skills source directory not found: $skillsSourceDir" }
            return emptyList()
        }

        val skillDirs = fileSystem.list(skillsSourceDir)
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }

        val destSkillsDir = Path(homeDir, target.toolDir, target.skillsSubdir)
        val copiedSkills = mutableListOf<String>()

        for (skillDir in skillDirs) {
            val skillName = skillDir.name
            val destDir = Path(destSkillsDir, skillName)
            destDir.create(fileSystem)
            fileCopier.copyDirectoryRecursively(skillDir, destDir, replacements)
            copiedSkills.add(skillName)
            logger.info { "${target.name}: synced skill '$skillName'" }
            retireAlertTriageSkill(destSkillsDir, target, skillName)
        }

        return copiedSkills
    }

    private fun retireAlertTriageSkill(
        destSkillsDir: Path,
        target: ToolTarget,
        installedSkillName: String,
    ) {
        if (installedSkillName != PUBLISHED_ALERT_TRIAGE_SKILL) return

        val retiredSkillDir = Path(destSkillsDir, RETIRED_ALERT_TRIAGE_SKILL)
        if (fileSystem.metadataOrNull(retiredSkillDir)?.isDirectory != true) return

        retiredSkillDir.deleteRecursively(fileSystem)
        logger.info { "${target.name}: retired skill '$RETIRED_ALERT_TRIAGE_SKILL'" }
    }

    private fun syncGuidelines(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacements: MarkdownReplacements,
    ): Boolean {
        var copied = false
        val guidelinesFileName = target.guidelinesFileName
        if (guidelinesFileName != null) {
            val agentsFile = Path(sourceLlmDir, "AGENTS.md")
            if (fileSystem.exists(agentsFile)) {
                val destFile = Path(homeDir, target.toolDir, guidelinesFileName)
                fileCopier.writeFileWithReplacement(agentsFile, destFile, replacements)
                logger.info { "${target.name}: synced guidelines → $guidelinesFileName" }
                copied = true
            } else {
                logger.warn { "Guidelines file not found: $agentsFile" }
            }
        }
        return copied
    }

    private fun syncNotes(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacements: MarkdownReplacements,
    ): Boolean {
        val notesFile = Path(sourceLlmDir, "notes.md")
        if (!fileSystem.exists(notesFile)) {
            logger.warn { "Notes file not found: $notesFile" }
            return false
        }

        val destFile = Path(homeDir, target.toolDir, "notes.md")
        fileCopier.writeFileWithReplacement(notesFile, destFile, replacements)
        logger.info { "${target.name}: synced notes → notes.md" }
        return true
    }
}

private data class MarkdownReplacements(
    val planningMarkdownDir: String?,
    val templateValues: Map<String, String>,
)

private class LlmFileCopier(
    private val fileSystem: FileSystem,
) {
    fun copyDirectoryRecursively(
        source: Path,
        dest: Path,
        replacements: MarkdownReplacements,
    ) {
        for (entry in fileSystem.list(source)) {
            val destEntry = Path(dest, entry.name)
            if (fileSystem.metadataOrNull(entry)?.isDirectory == true) {
                destEntry.create(fileSystem)
                copyDirectoryRecursively(entry, destEntry, replacements)
            } else {
                writeFileWithReplacement(entry, destEntry, replacements)
            }
        }
    }

    fun writeFileWithReplacement(
        source: Path,
        dest: Path,
        replacements: MarkdownReplacements,
    ) {
        (dest.parent ?: return).create(fileSystem)
        if (source.name.endsWith(".md")) {
            val content = fileSystem.source(source).buffered().use { it.readString() }
            val updatedContent = replaceMarkdownTemplates(content, replacements)
            fileSystem.sink(dest).buffered().use { it.writeString(updatedContent) }
            return
        }

        fileSystem.source(source).use { rawSource ->
            fileSystem.sink(dest).buffered().use { sink -> sink.transferFrom(rawSource) }
        }
    }
}

private fun replaceMarkdownTemplates(
    content: String,
    replacements: MarkdownReplacements,
): String {
    val contentWithPlanningDir = replacements.planningMarkdownDir?.let { planningMarkdownDir ->
        content.replace(PLANNING_MARKDOWN_DIR_TOKEN, planningMarkdownDir)
    } ?: content
    return markdownTemplateToken.replace(contentWithPlanningDir) { match ->
        val templateName = match.groupValues[1]
        if (templateName == PLANNING_MARKDOWN_DIR_NAME) {
            match.value
        } else {
            replacements.templateValues[templateName]?.takeUnless(String::isBlank)
                ?: missingTemplateValue(templateName)
        }
    }
}

private fun missingTemplateValue(templateName: String): String {
    logger.error {
        "LLM template value '$templateName' is missing or blank; installing corrective guidance"
    }
    return missingTemplateValuePrompt(templateName)
}

private fun isAbsolutePath(path: String): Boolean {
    val isUnixAbsolute = path.startsWith("/")
    val isWindowsAbsolute = windowsAbsolutePath.matches(path) || path.startsWith("\\\\")
    return isUnixAbsolute || isWindowsAbsolute
}

private fun Path.create(fileSystem: FileSystem) {
    if (!fileSystem.exists(this)) {
        fileSystem.createDirectories(this)
    }
}

private fun Path.deleteRecursively(fileSystem: FileSystem) {
    if (!isSymbolicLink() && fileSystem.metadataOrNull(this)?.isDirectory == true) {
        fileSystem.list(this).forEach { child -> child.deleteRecursively(fileSystem) }
    }
    fileSystem.delete(this)
}
