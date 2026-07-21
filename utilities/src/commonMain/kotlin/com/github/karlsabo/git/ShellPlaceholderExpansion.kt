package com.github.karlsabo.git

private typealias PlaceholderReplacement = Map.Entry<String, String>

internal enum class ShellDialect {
    POSIX,
    POWERSHELL,
}

internal fun String.expandShellPlaceholders(
    replacements: Map<String, String>,
    shellDialect: ShellDialect,
): String = ShellPlaceholderExpander(
    command = this,
    replacements = replacements,
    shellDialect = shellDialect,
).expand()

private enum class ShellQuoteContext {
    UNQUOTED,
    SINGLE_QUOTED,
    DOUBLE_QUOTED,
    ;

    fun afterReading(char: Char): ShellQuoteContext = when (this) {
        UNQUOTED -> when (char) {
            '\'' -> SINGLE_QUOTED
            '"' -> DOUBLE_QUOTED
            else -> this
        }

        SINGLE_QUOTED -> when (char) {
            '\'' -> UNQUOTED
            else -> this
        }

        DOUBLE_QUOTED -> when (char) {
            '"' -> UNQUOTED
            else -> this
        }
    }

    fun escape(value: String, shellDialect: ShellDialect): String = when (shellDialect) {
        ShellDialect.POSIX -> when (this) {
            UNQUOTED -> "'${value.escapeForSingleQuotedPosixShell()}'"
            SINGLE_QUOTED -> value.escapeForSingleQuotedPosixShell()
            DOUBLE_QUOTED -> value.escapeForDoubleQuotedPosixShell()
        }

        ShellDialect.POWERSHELL -> when (this) {
            UNQUOTED -> "'${value.escapeForSingleQuotedPowerShell()}'"
            SINGLE_QUOTED -> value.escapeForSingleQuotedPowerShell()
            DOUBLE_QUOTED -> value.escapeForDoubleQuotedPowerShell()
        }
    }
}

private class ShellPlaceholderExpander(
    private val command: String,
    private val replacements: Map<String, String>,
    private val shellDialect: ShellDialect,
) {
    private val expanded = StringBuilder()
    private var quoteContext = ShellQuoteContext.UNQUOTED
    private var index = 0

    fun expand(): String {
        while (index < command.length) {
            appendNextToken()
        }
        return expanded.toString()
    }

    private fun appendNextToken() {
        val replacement = currentReplacement()
        if (replacement == null) {
            appendCurrentChar()
        } else {
            appendReplacement(replacement)
        }
    }

    private fun currentReplacement(): PlaceholderReplacement? = replacements.entries.firstOrNull { (placeholder, _) ->
        command.startsWith(placeholder, index)
    }

    private fun appendReplacement(replacement: PlaceholderReplacement) {
        expanded.append(quoteContext.escape(replacement.value, shellDialect))
        index += replacement.key.length
    }

    private fun appendCurrentChar() {
        val char = command[index]
        val startingContext = quoteContext
        expanded.append(char)
        if (startingContext != ShellQuoteContext.SINGLE_QUOTED && char == shellDialect.escapeCharacter()) {
            appendEscapedChar()
        } else {
            quoteContext = startingContext.afterReading(char)
        }
        index += 1
    }

    private fun appendEscapedChar() {
        index += 1
        if (index < command.length) {
            expanded.append(command[index])
        }
    }
}

private fun ShellDialect.escapeCharacter(): Char = when (this) {
    ShellDialect.POSIX -> '\\'
    ShellDialect.POWERSHELL -> '`'
}

private fun String.escapeForSingleQuotedPosixShell(): String = replace("'", "'\\''")

private fun String.escapeForDoubleQuotedPosixShell(): String = buildString(length) {
    this@escapeForDoubleQuotedPosixShell.forEach { char ->
        when (char) {
            '$', '`', '"', '\\' -> append('\\')
        }
        append(char)
    }
}

private fun String.escapeForSingleQuotedPowerShell(): String = replace("'", "''")

private fun String.escapeForDoubleQuotedPowerShell(): String = buildString(length) {
    this@escapeForDoubleQuotedPowerShell.forEach { char ->
        when (char) {
            '$', '`', '"' -> append('`')
        }
        append(char)
    }
}
