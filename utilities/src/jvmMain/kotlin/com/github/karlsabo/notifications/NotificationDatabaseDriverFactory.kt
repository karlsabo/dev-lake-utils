package com.github.karlsabo.notifications

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class NotificationDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver {
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite:$databasePath")
        initializeSchema(driver)
        return driver
    }
}

private const val LEGACY_SCHEMA_VERSION = 1L
private const val UNINITIALIZED_SCHEMA_VERSION = 0L
private const val NOTIFICATION_TABLE = "unsubscribed_notification_threads"
private const val IGNORED_AT_COLUMN = "ignored_at_epoch_ms"

private fun initializeSchema(driver: JdbcSqliteDriver) {
    val transacter = object : TransacterImpl(driver) {}

    transacter.transaction {
        val version = driver.normalizeLegacySchemaVersion()

        when {
            version == UNINITIALIZED_SCHEMA_VERSION -> {
                NotificationDatabase.Schema.create(driver).value
                driver.setVersion(NotificationDatabase.Schema.version)
            }

            version < NotificationDatabase.Schema.version -> {
                NotificationDatabase.Schema.migrate(driver, version, NotificationDatabase.Schema.version).value
                driver.setVersion(NotificationDatabase.Schema.version)
            }
        }
    }
}

private fun JdbcSqliteDriver.normalizeLegacySchemaVersion(): Long {
    val version = getVersion()
    if (version != UNINITIALIZED_SCHEMA_VERSION || !hasNotificationTable()) {
        return version
    }

    val normalizedVersion =
        if (hasNotificationColumn(IGNORED_AT_COLUMN)) {
            NotificationDatabase.Schema.version
        } else {
            LEGACY_SCHEMA_VERSION
        }

    setVersion(normalizedVersion)
    return normalizedVersion
}

private fun JdbcSqliteDriver.hasNotificationTable(): Boolean {
    return queryHasRows(
        """
        SELECT 1
        FROM sqlite_master
        WHERE type = 'table' AND name = '$NOTIFICATION_TABLE'
        LIMIT 1
        """.trimIndent()
    )
}

private fun JdbcSqliteDriver.hasNotificationColumn(columnName: String): Boolean {
    return executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($NOTIFICATION_TABLE)",
        mapper = { cursor ->
            while (cursor.next().value) {
                if (cursor.getString(1) == columnName) {
                    return@executeQuery QueryResult.Value(true)
                }
            }
            QueryResult.Value(false)
        },
        parameters = 0,
        binders = null,
    ).value
}

private fun JdbcSqliteDriver.queryHasRows(sql: String): Boolean {
    return executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 0,
        binders = null,
    ).value
}

private fun JdbcSqliteDriver.getVersion(): Long {
    return executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        },
        parameters = 0,
        binders = null,
    ).value ?: UNINITIALIZED_SCHEMA_VERSION
}

private fun JdbcSqliteDriver.setVersion(version: Long) {
    execute(
        identifier = null,
        sql = "PRAGMA user_version = $version",
        parameters = 0,
        binders = null,
    ).value
}
