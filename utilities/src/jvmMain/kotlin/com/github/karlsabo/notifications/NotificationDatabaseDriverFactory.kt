package com.github.karlsabo.notifications

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class NotificationDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver {
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:$databasePath",
            schema = NotificationDatabase.Schema,
        )
    }
}
