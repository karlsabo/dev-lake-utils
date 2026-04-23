package com.github.karlsabo.notifications

import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.getVersion
import co.touchlab.sqliter.setVersion
import co.touchlab.sqliter.withConnection
import co.touchlab.sqliter.withStatement
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationDatabaseMigrationTest {

    @Test
    fun upgradesLegacyVersion1DatabaseWithUnsetUserVersionAndPreservesUnsubscribedThreadIds() {
        assertSchemaVersion1LayoutMigratesToCurrentSchema(initialUserVersion = null, expectedInitialUserVersion = 0L)
    }

    @Test
    fun upgradesVersion1DatabaseAndPreservesUnsubscribedThreadIds() {
        assertSchemaVersion1LayoutMigratesToCurrentSchema(initialUserVersion = 1, expectedInitialUserVersion = 1L)
    }

    @Test
    fun mapsDetectedLegacyLayoutsToConcreteSchemaVersions() {
        assertEquals(1, normalizedNotificationSchemaVersionForLegacyLayout(hasIgnoredAtColumn = false))
        assertEquals(2, normalizedNotificationSchemaVersionForLegacyLayout(hasIgnoredAtColumn = true))
    }

    private fun assertSchemaVersion1LayoutMigratesToCurrentSchema(
        initialUserVersion: Int?,
        expectedInitialUserVersion: Long,
    ) {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, "eng-hub-notifications.db")

        try {
            createSchemaVersion1Fixture(databasePath, initialUserVersion)
            assertEquals(expectedInitialUserVersion, readUserVersion(databasePath))

            val store = SqlDelightNotificationSubscriptionStore(databasePath = databasePath.toString())

            assertEquals(setOf("123456789"), store.listUnsubscribedThreadIds())
            assertEquals(NotificationDatabase.Schema.version.toLong(), readUserVersion(databasePath))
            assertEquals(
                listOf(
                    "thread_id",
                    "repository_full_name",
                    "subject_type",
                    "unsubscribed_at_epoch_ms",
                    "ignored_at_epoch_ms",
                ),
                readColumnNames(databasePath),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    private fun createSchemaVersion1Fixture(databasePath: Path, initialUserVersion: Int?) {
        withDatabaseConnection(databasePath) { connection ->
            connection.rawExecSql(
                """
                CREATE TABLE unsubscribed_notification_threads (
                  thread_id TEXT NOT NULL PRIMARY KEY,
                  repository_full_name TEXT NOT NULL,
                  subject_type TEXT NOT NULL,
                  unsubscribed_at_epoch_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.rawExecSql(
                """
                INSERT INTO unsubscribed_notification_threads(
                  thread_id,
                  repository_full_name,
                  subject_type,
                  unsubscribed_at_epoch_ms
                )
                VALUES ('123456789', 'example-org/example-repo', 'PullRequest', 1)
                """.trimIndent(),
            )
            if (initialUserVersion != null) {
                connection.setVersion(initialUserVersion)
            }
        }
    }

    private fun readUserVersion(databasePath: Path): Long {
        return withDatabaseConnection(databasePath) { connection ->
            connection.getVersion().toLong()
        }
    }

    private fun readColumnNames(databasePath: Path): List<String> {
        return withDatabaseConnection(databasePath) { connection ->
            connection.withStatement("PRAGMA table_info(unsubscribed_notification_threads)") {
                val cursor = query()
                val columnNames = mutableListOf<String>()
                try {
                    while (cursor.next()) {
                        columnNames += cursor.getString(1)
                    }
                    columnNames
                } finally {
                    resetStatement()
                }
            }
        }
    }
}

private fun <T> withDatabaseConnection(databasePath: Path, block: (co.touchlab.sqliter.DatabaseConnection) -> T): T {
    val databaseManager =
        createDatabaseManager(
            DatabaseConfiguration(
                name = databasePath.name,
                version = NO_VERSION_CHECK,
                create = {},
                extendedConfig =
                    DatabaseConfiguration.Extended(
                        basePath = databasePath.parent?.toString(),
                    ),
            ),
        )

    return databaseManager.withConnection(block)
}
