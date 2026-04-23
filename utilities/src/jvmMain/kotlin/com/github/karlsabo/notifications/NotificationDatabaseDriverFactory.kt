package com.github.karlsabo.notifications

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class NotificationDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver {
        val needsCreate = !File(databasePath).exists()
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite:$databasePath")
        if (needsCreate) {
            NotificationDatabase.Schema.create(driver)
        }
        return driver
    }
}
