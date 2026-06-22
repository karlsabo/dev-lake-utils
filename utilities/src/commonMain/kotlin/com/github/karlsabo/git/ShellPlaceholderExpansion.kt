package com.github.karlsabo.git

private typealias PlaceholderReplacement = Map.Entry<String, String>

internal fun String.expandShellPlaceholders(replacements: Map<String, String>): String = ShellPlaceholderExpander(
    command = this,
    replacements = replacements,
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

    fun escape(value: String): String = when (this) {
        UNQUOTED -> "'${value.replace("'", "'\\''")}'"
        SINGLE_QUOTED -> value.replace("'", "'\\''")
        DOUBLE_QUOTED -> value.escapeForDoubleQuotedShell()
    }
}

private class ShellPlaceholderExpander(
    private val command: String,
    private val replacements: Map<String, String>,
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
        expanded.append(quoteContext.escape(replacement.value))
        index += replacement.key.length
    }

    private fun appendCurrentChar() {
        val char = command[index]
        val startingContext = quoteContext
        expanded.append(char)
        if (startingContext != ShellQuoteContext.SINGLE_QUOTED && char == '\\') {
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

private fun String.escapeForDoubleQuotedShell(): String = buildString(length) {
    this@escapeForDoubleQuotedShell.forEach { char ->
        when (char) {
            '$', '`', '"', '\\' -> append('\\')
        }
        append(char)
    }
}
