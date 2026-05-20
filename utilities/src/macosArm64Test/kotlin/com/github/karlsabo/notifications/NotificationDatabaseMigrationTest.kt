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
    fun upgradesVersion1DatabaseAndPreservesUnsubscribedRowsAsIgnoredRows() {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, "eng-hub-notifications.db")

        try {
            createSchemaVersion1Fixture(databasePath)
            assertEquals(1L, readUserVersion(databasePath))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf("123456789"), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath))
            assertEquals(
                listOf(
                    "thread_id",
                    "repository_full_name",
                    "subject_type",
                    "ignore_reason",
                    "ignored_at_epoch_ms",
                ),
                readIgnoredColumnNames(databasePath),
            )
            assertEquals(
                listOf(IgnoredThreadRow("123456789", "example-org/example-repo", "PullRequest", "UNSUBSCRIBED", 1L)),
                readIgnoredRows(databasePath),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    @Test
    fun upgradesVersion2DatabaseAndPreservesIgnoredTimestampWhenPresent() {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, "eng-hub-notifications.db")

        try {
            createSchemaVersion2Fixture(databasePath)
            assertEquals(2L, readUserVersion(databasePath))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf("thread-with-ignore-timestamp"), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath))
            assertEquals(
                listOf(
                    IgnoredThreadRow(
                        "thread-with-ignore-timestamp",
                        "example-org/example-repo",
                        "PullRequest",
                        "UNSUBSCRIBED",
                        2L,
                    ),
                ),
                readIgnoredRows(databasePath),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    private fun createSchemaVersion1Fixture(databasePath: Path) {
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
            connection.setVersion(1)
        }
    }

    private fun createSchemaVersion2Fixture(databasePath: Path) {
        withDatabaseConnection(databasePath) { connection ->
            connection.rawExecSql(
                """
                CREATE TABLE unsubscribed_notification_threads (
                  thread_id TEXT NOT NULL PRIMARY KEY,
                  repository_full_name TEXT NOT NULL,
                  subject_type TEXT NOT NULL,
                  unsubscribed_at_epoch_ms INTEGER NOT NULL,
                  ignored_at_epoch_ms INTEGER
                )
                """.trimIndent(),
            )
            connection.rawExecSql(
                """
                INSERT INTO unsubscribed_notification_threads(
                  thread_id,
                  repository_full_name,
                  subject_type,
                  unsubscribed_at_epoch_ms,
                  ignored_at_epoch_ms
                )
                VALUES ('thread-with-ignore-timestamp', 'example-org/example-repo', 'PullRequest', 1, 2)
                """.trimIndent(),
            )
            connection.setVersion(2)
        }
    }

    private fun readUserVersion(databasePath: Path): Long {
        return withDatabaseConnection(databasePath) { connection ->
            connection.getVersion().toLong()
        }
    }

    private fun readIgnoredColumnNames(databasePath: Path): List<String> {
        return withDatabaseConnection(databasePath) { connection ->
            connection.withStatement("PRAGMA table_info(ignored_notification_threads)") {
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

    private fun readIgnoredRows(databasePath: Path): List<IgnoredThreadRow> {
        return withDatabaseConnection(databasePath) { connection ->
            connection.withStatement(
                """
                SELECT thread_id, repository_full_name, subject_type, ignore_reason, ignored_at_epoch_ms
                FROM ignored_notification_threads
                ORDER BY thread_id
                """.trimIndent(),
            ) {
                val cursor = query()
                val rows = mutableListOf<IgnoredThreadRow>()
                try {
                    while (cursor.next()) {
                        rows += IgnoredThreadRow(
                            threadId = cursor.getString(0),
                            repositoryFullName = cursor.getString(1),
                            subjectType = cursor.getString(2),
                            ignoreReason = cursor.getString(3),
                            ignoredAtEpochMs = cursor.getLong(4),
                        )
                    }
                    rows
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

private data class IgnoredThreadRow(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val ignoreReason: String,
    val ignoredAtEpochMs: Long,
)
