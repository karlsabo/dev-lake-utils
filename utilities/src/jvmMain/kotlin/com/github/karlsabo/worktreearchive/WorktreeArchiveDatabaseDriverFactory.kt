package com.github.karlsabo.worktreearchive

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class WorktreeArchiveDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver = JdbcSqliteDriver(
        url = "jdbc:sqlite:$databasePath",
        schema = WorktreeArchiveDatabase.Schema,
    )
}
