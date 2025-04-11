package com.github.karlsabo.devlake.tools

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Returns the application directory path based on the operating system.
 *
 * On macOS: `~/Library/Application Support/DevLakeUtils`
 * On Windows: `%APPDATA%\DevLakeUtils`
 * On Linux: `~/.local/share/DevLakeUtils`
 *
 * @return Path to the application directory.
 */
actual fun getApplicationDirectory(appName:String): Path {
    val userHome = Path(System.getProperty("user.home"))

    val directory = when (System.getProperty("os.name").lowercase()) {
        in listOf("mac os x", "mac os", "macos", "macosx") -> Path(userHome, "Library", "Application Support", appName)
        in listOf("windows") -> {
            val appDataEnv = System.getenv("APPDATA")
            val appData: Path = if (appDataEnv != null) Path(appDataEnv) else Path(userHome, "AppData", "Roaming")
            Path(appData, appName)
        }

        else -> {
            Path(userHome, ".local", "share", appName)
        }
    }
    if (!SystemFileSystem.exists(directory)) SystemFileSystem.createDirectories(directory)

    return directory
}
