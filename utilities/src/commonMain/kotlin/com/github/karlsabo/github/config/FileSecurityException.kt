package com.github.karlsabo.github.config

internal class FileSecurityException(
    val securityCause: Throwable,
) : RuntimeException(securityCause)

internal expect fun <T> translateFileSecurityException(operation: () -> T): T
