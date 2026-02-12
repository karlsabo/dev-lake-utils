package com.github.karlsabo.system

import java.io.File
import java.io.InputStream

private fun InputStream.readToString(): String {
    val builder = StringBuilder()
    val buffer = CharArray(8096)
    bufferedReader().use { reader ->
        var read: Int
        while (reader.read(buffer).also { read = it } != -1) {
            builder.appendRange(buffer, 0, read)
        }
    }
    return builder.toString()
}

actual fun executeCommand(command: List<String>, workingDirectory: String?): ProcessResult {
    val processBuilder = ProcessBuilder(command)
    if (workingDirectory != null) {
        processBuilder.directory(File(workingDirectory))
    }

    processBuilder.environment()["GIT_TERMINAL_PROMPT"] = "0"
    val process = processBuilder.start()

    var stderr = ""
    val stderrReader = Thread { stderr = process.errorStream.readToString() }.apply {
        isDaemon = true
        start()
    }

    val stdout = process.inputStream.readToString()

    stderrReader.join()
    val exitCode = process.waitFor()

    return ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}
