package com.github.karlsabo.system

import java.io.File

actual fun executeCommand(command: List<String>, workingDirectory: String?): ProcessResult {
    val processBuilder = ProcessBuilder(command)
    if (workingDirectory != null) {
        processBuilder.directory(File(workingDirectory))
    }
    val process = processBuilder.start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}
