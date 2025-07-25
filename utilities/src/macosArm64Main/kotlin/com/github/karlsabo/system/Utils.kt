package com.github.karlsabo.system

import platform.posix.getenv

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun getEnv(name: String): String? {
    val envValue = getenv(name) ?: return null
    return envValue.toKString()
}
