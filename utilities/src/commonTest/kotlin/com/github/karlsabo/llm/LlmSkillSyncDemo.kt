package com.github.karlsabo.llm

import com.github.karlsabo.system.getEnv
import kotlinx.io.files.Path

fun main() {
    val home = getEnv("HOME") ?: error("HOME not set")
    val sourceLlmDir = Path(home, "git", "dev-lake-utils", "llm")
    val homeDir = Path(home)

    val results = LlmSkillSync().syncAll(sourceLlmDir, homeDir)

    for (result in results) {
        println("${result.target.name}: ${result.skillsCopied.size} skills synced")
        result.skillsCopied.forEach { println("  - $it") }
        if (result.guidelinesCopied) println("  - guidelines -> ${result.target.guidelinesFileName}")
    }
}
