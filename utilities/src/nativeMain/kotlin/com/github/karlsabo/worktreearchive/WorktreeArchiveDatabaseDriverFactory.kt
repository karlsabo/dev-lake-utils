package com.github.karlsabo.worktreearchive

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.io.files.Path

actual class WorktreeArchiveDatabaseDriverFactory {
    actual fun createDriver(databasePath: String): SqlDriver {
        val path = Path(databasePath)
        return NativeSqliteDriver(
            schema = WorktreeArchiveDatabase.Schema,
            name = path.name,
            onConfiguration = { configuration ->
                configuration.configureWorktreeArchiveDatabase(path.parent?.toString())
            },
        )
    }
}

private fun DatabaseConfiguration.configureWorktreeArchiveDatabase(basePath: String?): DatabaseConfiguration = copy(
    extendedConfig = extendedConfig.copy(
        basePath = basePath ?: extendedConfig.basePath,
    ),
)
