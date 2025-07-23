package com.github.karlsabo.tools

import com.github.karlsabo.dto.UsersConfig
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

const val DEV_METRICS_APP_NAME = "DevLakeUtils"
val textSummarizerConfigPath = Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "text-summarizer-openai-config.json")
val jiraConfigPath = Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "jira-rest-config.json")
val gitHubConfigPath = Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "github-config.json")
val pagerDutyConfigPath = Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "pagerduty-config.json")
val usersConfigPath = Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "users-and-teams.json")

fun loadUsersConfig(): UsersConfig? {
    if (!SystemFileSystem.exists(usersConfigPath)) {
        return null
    }
    return try {
        lenientJson.decodeFromString(
            UsersConfig.serializer(),
            SystemFileSystem.source(usersConfigPath).buffered().readText(),
        )
    } catch (error: Exception) {
        println("Failed to load user config: $error")
        return null
    }
}

private fun saveUserConfig(userConfig: UsersConfig) {
    SystemFileSystem.sink(usersConfigPath).buffered().use {
        it.writeString(
            lenientJson.encodeToString(
                UsersConfig.serializer(),
                userConfig,
            )
        )
    }
}

/**
 * Returns the application directory path based on the operating system.
 *
 * On macOS: `~/Library/Application Support/DevLakeUtils`
 * On Windows: `%APPDATA%\DevLakeUtils`
 * On Linux: `~/.local/share/DevLakeUtils`
 *
 * @return Path to the application directory.
 */
fun getApplicationDirectory(appName: String): Path {
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
