package com.github.karlsabo.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/**
 * Each tool target defines where skills and guidelines land.
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
    PI(".agent", "skills", "AGENTS.MD"),
}

data class SyncResult(
    val target: ToolTarget,
    val skillsCopied: List<String>,
    val guidelinesCopied: Boolean,
)

/**
 * Syncs LLM skill files from a central source directory to the correct locations
 * for Claude Code, OpenAI Codex CLI, and Block Goose CLI.
 */
class LlmSkillSync(
    private val fileSystem: FileSystem = SystemFileSystem,
) {
    /** Sync skills and guidelines to a single tool target. */
    fun sync(sourceLlmDir: Path, homeDir: Path, target: ToolTarget): SyncResult {
        val skillsCopied = syncSkills(sourceLlmDir, homeDir, target)
        val guidelinesCopied = syncGuidelines(sourceLlmDir, homeDir, target)
        return SyncResult(target, skillsCopied, guidelinesCopied)
    }

    /** Sync to all tool targets. */
    fun syncAll(
        sourceLlmDir: Path,
        homeDir: Path,
        targets: List<ToolTarget> = ToolTarget.entries,
    ): List<SyncResult> = targets.map { sync(sourceLlmDir, homeDir, it) }

    private fun syncSkills(sourceLlmDir: Path, homeDir: Path, target: ToolTarget): List<String> {
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
            copyDirectoryRecursively(skillDir, destDir)
            copiedSkills.add(skillName)
            logger.info { "${target.name}: synced skill '$skillName'" }
        }

        return copiedSkills
    }

    private fun syncGuidelines(sourceLlmDir: Path, homeDir: Path, target: ToolTarget): Boolean {
        val guidelinesFileName = target.guidelinesFileName ?: return false

        val agentsFile = Path(sourceLlmDir, "AGENTS.md")
        if (!fileSystem.exists(agentsFile)) {
            logger.warn { "Guidelines file not found: $agentsFile" }
            return false
        }

        val destFile = Path(homeDir, target.toolDir, guidelinesFileName)
        (destFile.parent ?: Path(homeDir, target.toolDir)).create()
        agentsFile.copyTo(destFile)
        logger.info { "${target.name}: synced guidelines → $guidelinesFileName" }
        return true
    }

    private fun copyDirectoryRecursively(source: Path, dest: Path) {
        for (entry in fileSystem.list(source)) {
            val destEntry = Path(dest, entry.name)
            if (fileSystem.metadataOrNull(entry)?.isDirectory == true) {
                destEntry.create()
                copyDirectoryRecursively(entry, destEntry)
            } else {
                entry.copyTo(destEntry)
            }
        }
    }

    private fun Path.copyTo(dest: Path) {
        fileSystem.source(this).use { rawSource ->
            fileSystem.sink(dest).buffered().use { sink -> sink.transferFrom(rawSource) }
        }
    }

    private fun Path.create() {
        if (!fileSystem.exists(this)) {
            fileSystem.createDirectories(this)
        }
    }
}
