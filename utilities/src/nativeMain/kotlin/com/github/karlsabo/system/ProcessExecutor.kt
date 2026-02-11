package com.github.karlsabo.system

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

actual fun executeCommand(command: List<String>, workingDirectory: String?): ProcessResult {
    val escapedArgs = command.joinToString(" ") { arg ->
        "'" + arg.replace("'", "'\\''") + "'"
    }
    val fullCommand = buildString {
        if (workingDirectory != null) {
            append("cd '")
            append(workingDirectory.replace("'", "'\\''"))
            append("' && ")
        }
        append(escapedArgs)
        append(" 2>&1")
    }

    val fp = popen(fullCommand, "r")
        ?: return ProcessResult(exitCode = -1, stdout = "", stderr = "Failed to execute command")

    val output = buildString {
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            while (fgets(buffer, 4096, fp) != null) {
                append(buffer.toKString())
            }
        }
    }

    val status = pclose(fp)
    val exitCode = (status shr 8) and 0xFF

    return ProcessResult(exitCode = exitCode, stdout = output, stderr = "")
}
