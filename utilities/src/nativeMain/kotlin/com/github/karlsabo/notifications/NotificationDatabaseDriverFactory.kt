package com.github.karlsabo.notifications

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class NotificationDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver {
        return NativeSqliteDriver(
            schema = NotificationDatabase.Schema,
            name = databasePath,
        )
    }
}
