package com.github.karlsabo.notifications

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.getVersion
import co.touchlab.sqliter.setVersion
import co.touchlab.sqliter.withStatement
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

// These values describe fixed on-disk layouts for legacy notification tables.
// They must stay stable even when NotificationDatabase.Schema.version increases.
private const val NOTIFICATION_SCHEMA_VERSION_1 = 1
private const val NOTIFICATION_SCHEMA_VERSION_2 = 2
private const val UNINITIALIZED_SCHEMA_VERSION = 0
private const val NOTIFICATION_TABLE = "unsubscribed_notification_threads"
private const val IGNORED_AT_COLUMN = "ignored_at_epoch_ms"

private fun DatabaseConfiguration.configureNotificationDatabase(basePath: String?): DatabaseConfiguration {
    val existingLifecycle = lifecycleConfig
    return copy(
        extendedConfig =
            extendedConfig.copy(
                basePath = basePath ?: extendedConfig.basePath,
            ),
        lifecycleConfig =
            lifecycleConfig.copy(
                onCreateConnection = { connection ->
                    existingLifecycle.onCreateConnection(connection)
                    connection.normalizeLegacyNotificationSchemaVersion()
                },
            ),
    )
}

private fun DatabaseConnection.normalizeLegacyNotificationSchemaVersion() {
    // SQLiter treats user_version=0 as "run create", so a legacy v1 file has to be
    // normalized before its built-in create/upgrade decision or the migration is skipped.
    val version = getVersion()
    if (version != UNINITIALIZED_SCHEMA_VERSION || !hasNotificationTable()) {
        return
    }

    setVersion(
        normalizedNotificationSchemaVersionForLegacyLayout(
            hasIgnoredAtColumn = hasNotificationColumn(IGNORED_AT_COLUMN),
        ),
    )
}

internal fun normalizedNotificationSchemaVersionForLegacyLayout(hasIgnoredAtColumn: Boolean): Int =
    if (hasIgnoredAtColumn) {
        NOTIFICATION_SCHEMA_VERSION_2
    } else {
        NOTIFICATION_SCHEMA_VERSION_1
    }

private fun DatabaseConnection.hasNotificationTable(): Boolean {
    return queryHasRows(
        """
        SELECT 1
        FROM sqlite_master
        WHERE type = 'table' AND name = '$NOTIFICATION_TABLE'
        LIMIT 1
        """.trimIndent(),
    )
}

private fun DatabaseConnection.hasNotificationColumn(columnName: String): Boolean {
    return withStatement("PRAGMA table_info($NOTIFICATION_TABLE)") {
        val cursor = query()
        try {
            while (cursor.next()) {
                if (cursor.getString(1) == columnName) {
                    return@withStatement true
                }
            }
            false
        } finally {
            resetStatement()
        }
    }
}

private fun DatabaseConnection.queryHasRows(sql: String): Boolean {
    return withStatement(sql) {
        val cursor = query()
        try {
            cursor.next()
        } finally {
            resetStatement()
        }
    }
}
