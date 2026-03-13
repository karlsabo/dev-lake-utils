package com.github.karlsabo.llm

import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv

fun main() {
    val home = getenv("HOME")?.toKString() ?: error("HOME not set")
    val sourceLlmDir = Path(home, "git", "dev-lake-utils", "llm")
    val homeDir = Path(home)

    val results = LlmSkillSync().syncAll(sourceLlmDir, homeDir)

    for (result in results) {
        println("${result.target.name}: ${result.skillsCopied.size} skills synced")
        result.skillsCopied.forEach { println("  - $it") }
        if (result.guidelinesCopied) println("  - guidelines -> ${result.target.guidelinesFileName}")
    }
}
