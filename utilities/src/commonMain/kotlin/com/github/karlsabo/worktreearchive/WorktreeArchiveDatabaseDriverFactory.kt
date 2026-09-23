package com.github.karlsabo.worktreearchive

import app.cash.sqldelight.db.SqlDriver

expect class WorktreeArchiveDatabaseDriverFactory() {
    fun createDriver(databasePath: String): SqlDriver
}
