package com.github.karlsabo.system

import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun getEnv(name: String): String? {
    val envValue = getenv(name) ?: return null
    return envValue.toKString()
}

actual fun osFamily(): OsFamily = when (Platform.osFamily) {
    kotlin.native.OsFamily.MACOSX -> OsFamily.MACOS
    kotlin.native.OsFamily.LINUX -> OsFamily.LINUX
    kotlin.native.OsFamily.WINDOWS -> OsFamily.WINDOWS
    else -> OsFamily.UNKNOWN
}
