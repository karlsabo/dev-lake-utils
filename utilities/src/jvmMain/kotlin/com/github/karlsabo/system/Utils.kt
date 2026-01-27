package com.github.karlsabo.system

actual fun getEnv(name: String): String? = System.getenv(name)

actual fun osFamily(): OsFamily {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") || osName.contains("darwin") -> OsFamily.MACOS
        osName.contains("win") -> OsFamily.WINDOWS
        osName.contains("linux") || osName.contains("nix") || osName.contains("nux") -> OsFamily.LINUX
        else -> OsFamily.UNKNOWN
    }
}
