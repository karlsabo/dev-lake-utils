package com.github.karlsabo.git

import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily

internal fun String.matchesArchivePath(persistedPath: String): Boolean {
    if (this == persistedPath) return true
    return when (osFamily()) {
        OsFamily.MACOS, OsFamily.WINDOWS -> equals(persistedPath, ignoreCase = true)
        OsFamily.LINUX, OsFamily.UNKNOWN -> false
    }
}
