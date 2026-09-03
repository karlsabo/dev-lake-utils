package com.github.karlsabo.devlake.enghub

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmSkillSyncJvmTest {
    @Test
    fun retiringWipAlertTriageDeletesSymlinkWithoutTraversingDirectoryTarget() = withTempDirectory { root ->
        val sourceDir = root.resolve("source")
        val homeDir = root.resolve("home")
        val publishedSkillDir = sourceDir.resolve(".agents/skills/eh-alert-triage")
        val retiredSkillDir = homeDir.resolve(".pi/agent/skills/wip-eh-alert-triage")
        val externalDir = root.resolve("external")
        val externalMarker = externalDir.resolve("marker.txt")
        val linkedExternalDir = retiredSkillDir.resolve("linked-external")
        Files.createDirectories(publishedSkillDir)
        Files.createDirectories(retiredSkillDir)
        Files.createDirectories(externalDir)
        publishedSkillDir.resolve("SKILL.md").writeText("published")
        externalMarker.writeText("preserve me")
        Files.createSymbolicLink(linkedExternalDir, externalDir)

        LlmSkillSync().sync(
            Path(sourceDir.toString()),
            Path(homeDir.toString()),
            ToolTarget.PI,
            root.resolve("planning").toString(),
        )

        assertFalse(Files.exists(retiredSkillDir, NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(externalDir))
        assertEquals("preserve me", Files.readString(externalMarker))
    }

    private fun withTempDirectory(test: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("llm-skill-sync-")
        try {
            test(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
