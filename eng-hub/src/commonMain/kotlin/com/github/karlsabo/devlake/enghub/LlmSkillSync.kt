package com.github.karlsabo.devlake.enghub

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

private val logger = KotlinLogging.logger {}
private const val planningMarkdownDirToken = "\${PLANNING_MARKDOWN_DIR}"

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
    /** Sync skills, shared notes, and guidelines to a single tool target. */
    fun sync(sourceLlmDir: Path, homeDir: Path, target: ToolTarget, planningMarkdownDir: String): SyncResult {
        val replacementDir = preparePlanningMarkdownDir(planningMarkdownDir)
        return syncPrepared(sourceLlmDir, homeDir, target, replacementDir)
    }

    /** Sync to all tool targets. */
    fun syncAll(
        sourceLlmDir: Path,
        homeDir: Path,
        planningMarkdownDir: String,
        targets: List<ToolTarget> = ToolTarget.entries,
    ): List<SyncResult> {
        val replacementDir = preparePlanningMarkdownDir(planningMarkdownDir)
        return targets.map { syncPrepared(sourceLlmDir, homeDir, it, replacementDir) }
    }

    private fun syncPrepared(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacementDir: String?,
    ): SyncResult {
        val skillsCopied = syncSkills(sourceLlmDir, homeDir, target, replacementDir)
        val guidelinesCopied = syncGuidelines(sourceLlmDir, homeDir, target, replacementDir)
        val notesCopied = syncNotes(sourceLlmDir, homeDir, target, replacementDir)
        return SyncResult(target, skillsCopied, guidelinesCopied, notesCopied)
    }

    private fun preparePlanningMarkdownDir(planningMarkdownDir: String): String? {
        val trimmed = planningMarkdownDir.trim()
        if (trimmed.isBlank()) {
            logger.error { "planningMarkdownDir is blank; copying markdown files without replacing $planningMarkdownDirToken" }
            return null
        }

        require(isAbsolutePath(trimmed)) {
            "planningMarkdownDir must be an absolute path, got: $trimmed"
        }

        Path(trimmed).create()
        return trimmed
    }

    private fun syncSkills(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacementDir: String?,
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
            destDir.create()
            copyDirectoryRecursively(skillDir, destDir, replacementDir)
            copiedSkills.add(skillName)
            logger.info { "${target.name}: synced skill '$skillName'" }
        }

        return copiedSkills
    }

    private fun syncGuidelines(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacementDir: String?,
    ): Boolean {
        val guidelinesFileName = target.guidelinesFileName ?: return false
        val agentsFile = Path(sourceLlmDir, "AGENTS.md")
        if (!fileSystem.exists(agentsFile)) {
            logger.warn { "Guidelines file not found: $agentsFile" }
            return false
        }

        val destFile = Path(homeDir, target.toolDir, guidelinesFileName)
        writeFileWithReplacement(agentsFile, destFile, replacementDir)
        logger.info { "${target.name}: synced guidelines → $guidelinesFileName" }
        return true
    }

    private fun syncNotes(
        sourceLlmDir: Path,
        homeDir: Path,
        target: ToolTarget,
        replacementDir: String?,
    ): Boolean {
        val notesFile = Path(sourceLlmDir, "notes.md")
        if (!fileSystem.exists(notesFile)) {
            logger.warn { "Notes file not found: $notesFile" }
            return false
        }

        val destFile = Path(homeDir, target.toolDir, "notes.md")
        writeFileWithReplacement(notesFile, destFile, replacementDir)
        logger.info { "${target.name}: synced notes → notes.md" }
        return true
    }

    private fun copyDirectoryRecursively(source: Path, dest: Path, replacementDir: String?) {
        for (entry in fileSystem.list(source)) {
            val destEntry = Path(dest, entry.name)
            if (fileSystem.metadataOrNull(entry)?.isDirectory == true) {
                destEntry.create()
                copyDirectoryRecursively(entry, destEntry, replacementDir)
            } else {
                writeFileWithReplacement(entry, destEntry, replacementDir)
            }
        }
    }

    private fun writeFileWithReplacement(source: Path, dest: Path, replacementDir: String?) {
        (dest.parent ?: return).create()
        if (source.name.endsWith(".md")) {
            val content = fileSystem.source(source).buffered().use { it.readString() }
            val updatedContent = replacementDir?.let { content.replace(planningMarkdownDirToken, it) } ?: content
            fileSystem.sink(dest).buffered().use { it.writeString(updatedContent) }
            return
        }

        fileSystem.source(source).use { rawSource ->
            fileSystem.sink(dest).buffered().use { sink -> sink.transferFrom(rawSource) }
        }
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || windowsAbsolutePath.matches(path) || path.startsWith("\\\\")

    private fun Path.create() {
        if (!fileSystem.exists(this)) {
            fileSystem.createDirectories(this)
        }
    }

    private companion object {
        val windowsAbsolutePath = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
