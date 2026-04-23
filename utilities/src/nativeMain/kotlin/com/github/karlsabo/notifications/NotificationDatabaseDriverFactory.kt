package com.github.karlsabo.notifications

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.io.files.Path

actual class NotificationDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver {
        val path = Path(databasePath)
        return NativeSqliteDriver(
            schema = NotificationDatabase.Schema,
            name = path.name,
            onConfiguration = { configuration ->
                configuration.configureNotificationDatabase(path.parent?.toString())
            },
        )
    }
}

private fun DatabaseConfiguration.configureNotificationDatabase(basePath: String?): DatabaseConfiguration {
    return copy(
        extendedConfig =
            extendedConfig.copy(
                basePath = basePath ?: extendedConfig.basePath,
            ),
    )
}
