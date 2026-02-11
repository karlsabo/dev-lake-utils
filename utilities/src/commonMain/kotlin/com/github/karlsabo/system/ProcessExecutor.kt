package com.github.karlsabo.system

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

expect fun executeCommand(command: List<String>, workingDirectory: String? = null): ProcessResult
