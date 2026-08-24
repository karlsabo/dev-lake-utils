package com.github.karlsabo.github.config

internal actual fun <T> translateFileSecurityException(operation: () -> T): T = operation()
