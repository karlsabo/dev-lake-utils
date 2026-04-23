package com.github.karlsabo.notifications

import app.cash.sqldelight.db.SqlDriver

expect class NotificationDatabaseDriverFactory() {
    fun createDriver(databasePath: String): SqlDriver
}
