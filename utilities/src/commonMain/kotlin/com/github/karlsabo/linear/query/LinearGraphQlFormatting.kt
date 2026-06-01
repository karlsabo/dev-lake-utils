package com.github.karlsabo.linear.query

internal fun escapeLinearGraphQlString(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

internal fun String.indentGraphQl(prefix: String): String = lines().joinToString("\n") { line ->
    if (line.isBlank()) line else prefix + line
}

internal fun StringBuilder.appendLinearCursor(cursor: String?) {
    if (!cursor.isNullOrBlank()) {
        append(", after: \"")
        append(escapeLinearGraphQlString(cursor))
        append("\"")
    }
}
