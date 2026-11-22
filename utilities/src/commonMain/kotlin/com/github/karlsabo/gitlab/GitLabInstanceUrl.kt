package com.github.karlsabo.gitlab

import io.ktor.http.URLProtocol
import io.ktor.http.Url

private const val HTTPS_PREFIX = "https://"

/**
 * Returns the canonical instance base URL, or null unless [value] is an absolute HTTPS URL without credentials,
 * query, or fragment. The path prefix is preserved so instances served under a relative URL root work.
 */
fun normalizeGitLabInstanceUrl(value: String): String? {
    val trimmed = value.trim()
    val parsed = trimmed.takeIf { it.isNotEmpty() }?.let { candidate ->
        runCatching { Url(candidate) }.getOrNull()
    }
    val isValid = parsed != null &&
        parsed.protocol == URLProtocol.HTTPS &&
        parsed.host.isNotBlank() &&
        parsed.user.isNullOrEmpty() &&
        parsed.password.isNullOrEmpty() &&
        parsed.parameters.names().isEmpty() &&
        parsed.fragment.isEmpty()
    return if (isValid) trimmed.trimEnd('/').takeIf { it.length > HTTPS_PREFIX.length } else null
}

fun isValidGitLabInstanceUrl(value: String): Boolean = normalizeGitLabInstanceUrl(value) != null
