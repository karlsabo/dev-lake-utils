package com.github.karlsabo.git

class GitCommandException(
    val command: List<String>,
    val exitCode: Int,
    val gitOutput: String,
    message: String = "Git command failed (exit code $exitCode): ${command.joinToString(" ")} — $gitOutput",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
