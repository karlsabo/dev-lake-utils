package com.github.karlsabo.system

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

private const val COMMAND_FAILED_EXIT_CODE = -1
private const val PROCESS_OUTPUT_BUFFER_SIZE = 4_096
private const val EXIT_CODE_SHIFT_BITS = 8
private const val EXIT_CODE_MASK = 0xFF

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
        ?: return ProcessResult(exitCode = COMMAND_FAILED_EXIT_CODE, stdout = "", stderr = "Failed to execute command")

    val output = buildString {
        memScoped {
            val buffer = allocArray<ByteVar>(PROCESS_OUTPUT_BUFFER_SIZE)
            while (fgets(buffer, PROCESS_OUTPUT_BUFFER_SIZE, fp) != null) {
                append(buffer.toKString())
            }
        }
    }

    val status = pclose(fp)
    val exitCode = (status shr EXIT_CODE_SHIFT_BITS) and EXIT_CODE_MASK

    return ProcessResult(exitCode = exitCode, stdout = output, stderr = "")
}
