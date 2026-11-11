package com.github.karlsabo.devlake.enghub.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Reads an explicit MCP config document for the agent launchers. */
internal fun readMcpConfigFile(path: String): String = try {
    Files.readString(Path.of(path))
} catch (failure: IOException) {
    throw IllegalArgumentException("Cannot read MCP config '$path': ${failure.message}", failure)
}
