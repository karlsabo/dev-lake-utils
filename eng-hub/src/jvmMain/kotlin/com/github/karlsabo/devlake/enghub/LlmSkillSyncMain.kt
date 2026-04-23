package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.system.getEnv
import kotlinx.io.files.Path

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Expected the repo-relative llm source directory path as the first argument." }

    val home = getEnv("HOME") ?: getEnv("USERPROFILE") ?: error("Neither HOME nor USERPROFILE is set")
    val sourceLlmDir = Path(args[0])
    val homeDir = Path(home)
    val config = loadEngHubConfig()

    val results = LlmSkillSync().syncAll(sourceLlmDir, homeDir, config.planningMarkdownDir)

    for (result in results) {
        println("${result.target.name}: ${result.skillsCopied.size} skills synced")
        result.skillsCopied.forEach { println("  - $it") }
        if (result.guidelinesCopied) println("  - guidelines -> ${result.target.guidelinesFileName}")
        if (result.notesCopied) println("  - notes -> notes.md")
    }
}
