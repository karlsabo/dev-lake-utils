package com.github.karlsabo.git

import com.github.karlsabo.system.ProcessResult

private const val START_MARKER_FIELD_COUNT = 2
private const val RESULT_MARKER_FIELD_COUNT = 3

internal data class SetupCommandExecution(
    val commandIndex: Int,
    val command: String,
    val started: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

internal fun parseSetupCommandExecutions(
    setupCommands: List<String>,
    result: ProcessResult,
): List<SetupCommandExecution> {
    val metadata = result.stderr + "\n" + result.stdout
    val startedCommands = metadata.parseSetupCommandStarts()
    val exitCodes = metadata.parseSetupCommandExitCodes()
    val stdoutByCommand = result.stdout.parseSetupCommandStream(
        SETUP_COMMAND_STDOUT_BEGIN_MARKER,
        SETUP_COMMAND_STDOUT_END_MARKER,
    )
    val stderrByCommand = result.stderr.parseSetupCommandStream(
        SETUP_COMMAND_STDERR_BEGIN_MARKER,
        SETUP_COMMAND_STDERR_END_MARKER,
    ).ifEmpty {
        result.stdout.parseSetupCommandStream(
            SETUP_COMMAND_STDERR_BEGIN_MARKER,
            SETUP_COMMAND_STDERR_END_MARKER,
        )
    }
    val fallbackFailedCommand = startedCommands.lastOrNull()?.takeIf { result.exitCode != 0 && it !in exitCodes }

    return setupCommands.mapIndexed { index, command ->
        SetupCommandExecution(
            commandIndex = index,
            command = command,
            started = index in startedCommands,
            exitCode = exitCodes[index] ?: if (index == fallbackFailedCommand) result.exitCode else null,
            stdout = stdoutByCommand[index].orEmpty(),
            stderr = stderrByCommand[index].orEmpty(),
        )
    }
}

internal fun String.withoutSetupCommandMarkers(
    streamBeginMarker: String,
    streamEndMarker: String,
): String {
    val outputByCommand = parseSetupCommandStream(streamBeginMarker, streamEndMarker)
    return if (outputByCommand.isNotEmpty()) {
        outputByCommand.joinSetupCommandOutput()
    } else {
        val lines = lineSequence()
            .filterNot { it.startsWith("$SETUP_COMMAND_START_MARKER\t") }
            .filterNot { it.startsWith("$SETUP_COMMAND_RESULT_MARKER\t") }
            .filterNot { it.startsWith("$SETUP_COMMAND_FAILURE_MARKER\t") }
            .filterNot { it.startsWith("$streamBeginMarker\t") }
            .filterNot { it.startsWith("$streamEndMarker\t") }
            .toList()
        if (lines.isEmpty()) "" else lines.joinToString("\n") + if (endsWith('\n')) "\n" else ""
    }
}

internal fun String.setupCommandStreamOutput(
    streamBeginMarker: String,
    streamEndMarker: String,
): String = parseSetupCommandStream(streamBeginMarker, streamEndMarker).joinSetupCommandOutput()

private fun String.parseSetupCommandStarts(): Set<Int> = lineSequence()
    .mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size == START_MARKER_FIELD_COUNT && parts[0] == SETUP_COMMAND_START_MARKER) {
            parts[1].toIntOrNull()
        } else {
            null
        }
    }
    .toSet()

private fun String.parseSetupCommandExitCodes(): Map<Int, Int> = buildMap {
    lineSequence().forEach { line ->
        val parts = line.split('\t')
        if (parts.size == RESULT_MARKER_FIELD_COUNT && parts[0].isSetupCommandExitCodeMarker()) {
            val commandIndex = parts[1].toIntOrNull() ?: return@forEach
            val exitCode = parts[2].toIntOrNull() ?: return@forEach
            put(commandIndex, exitCode)
        }
    }
}

private fun String.isSetupCommandExitCodeMarker(): Boolean = when (this) {
    SETUP_COMMAND_RESULT_MARKER,
    SETUP_COMMAND_FAILURE_MARKER,
    -> true

    else -> false
}

private fun String.parseSetupCommandStream(
    beginMarker: String,
    endMarker: String,
): Map<Int, String> = buildMap {
    var searchIndex = 0
    while (searchIndex < this@parseSetupCommandStream.length) {
        val beginIndex = this@parseSetupCommandStream.indexOf(beginMarker, startIndex = searchIndex)
        if (beginIndex == -1) return@buildMap
        val beginLineEnd = this@parseSetupCommandStream.indexOf('\n', startIndex = beginIndex)
        if (beginLineEnd == -1) return@buildMap
        val commandIndex = this@parseSetupCommandStream.substring(beginIndex, beginLineEnd)
            .split('\t')
            .getOrNull(1)
            ?.toIntOrNull()
        if (commandIndex == null) {
            searchIndex = beginLineEnd + 1
            continue
        }
        val contentStart = beginLineEnd + 1
        val endToken = "$endMarker\t$commandIndex\t"
        val endIndex = this@parseSetupCommandStream.indexOf(endToken, startIndex = contentStart)
        if (endIndex == -1) return@buildMap
        val endLineEnd = this@parseSetupCommandStream.indexOf('\n', startIndex = endIndex)
        if (endLineEnd == -1) return@buildMap
        put(commandIndex, this@parseSetupCommandStream.substring(contentStart, endIndex))
        searchIndex = endLineEnd + 1
    }
}

private fun Map<Int, String>.joinSetupCommandOutput(): String = keys.sorted()
    .joinToString(separator = "") { index -> getValue(index) }
