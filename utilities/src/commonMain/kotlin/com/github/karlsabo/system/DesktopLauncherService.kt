package com.github.karlsabo.system

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class DesktopLauncherService : DesktopLauncher {

    override fun openUrl(url: String) {
        val command = when (osFamily()) {
            OsFamily.MACOS -> listOf("open", url)
            OsFamily.LINUX -> listOf("xdg-open", url)
            OsFamily.WINDOWS -> listOf("cmd", "/c", "start", url)
            OsFamily.UNKNOWN -> {
                logger.warn { "Unknown OS family, attempting xdg-open" }
                listOf("xdg-open", url)
            }
        }
        val result = executeCommand(command)
        if (result.exitCode != 0) {
            logger.error { "Failed to open URL $url: ${result.stderr.ifEmpty { result.stdout }}" }
        }
    }

    override fun openInIdea(projectPath: String) {
        val result = executeCommand(listOf("idea", projectPath))
        if (result.exitCode != 0) {
            val detail = result.stderr.ifEmpty { result.stdout }
            throw RuntimeException("Failed to open IntelliJ IDEA for $projectPath (exit code ${result.exitCode}): $detail")
        }
    }
}
