package com.github.karlsabo.notifications

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationDatabaseMigrationTest {

    @Test
    fun upgradesVersion1DatabaseAndPreservesUnsubscribedRowsAsIgnoredRows() {
        val testDir = Files.createTempDirectory("notification-db-migration-test")
        val databasePath = testDir.resolve("eng-hub-notifications.db")

        try {
            createVersion1Fixture(databasePath.toString())
            assertEquals(1L, readUserVersion(databasePath.toString()))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf("123456789"), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath.toString()))
            assertEquals(
                listOf(
                    "thread_id",
                    "repository_full_name",
                    "subject_type",
                    "ignore_reason",
                    "ignored_at_epoch_ms",
                ),
                readIgnoredColumnNames(databasePath.toString()),
            )
            assertEquals(
                listOf(IgnoredThreadRow("123456789", "example-org/example-repo", "PullRequest", "UNSUBSCRIBED", 1L)),
                readIgnoredRows(databasePath.toString()),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    @Test
    fun upgradesVersion2DatabaseAndPreservesIgnoredTimestampWhenPresent() {
        val testDir = Files.createTempDirectory("notification-db-migration-test")
        val databasePath = testDir.resolve("eng-hub-notifications.db")

        try {
            createVersion2Fixture(databasePath.toString())
            assertEquals(2L, readUserVersion(databasePath.toString()))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf("thread-with-ignore-timestamp"), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath.toString()))
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
                readIgnoredRows(databasePath.toString()),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    private fun createVersion1Fixture(databasePath: String) {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE unsubscribed_notification_threads (
                      thread_id TEXT NOT NULL PRIMARY KEY,
                      repository_full_name TEXT NOT NULL,
                      subject_type TEXT NOT NULL,
                      unsubscribed_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO unsubscribed_notification_threads(
                      thread_id,
                      repository_full_name,
                      subject_type,
                      unsubscribed_at_epoch_ms
                    )
                    VALUES ('123456789', 'example-org/example-repo', 'PullRequest', 1)
                    """.trimIndent()
                )
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }
    }

    private fun createVersion2Fixture(databasePath: String) {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE unsubscribed_notification_threads (
                      thread_id TEXT NOT NULL PRIMARY KEY,
                      repository_full_name TEXT NOT NULL,
                      subject_type TEXT NOT NULL,
                      unsubscribed_at_epoch_ms INTEGER NOT NULL,
                      ignored_at_epoch_ms INTEGER
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO unsubscribed_notification_threads(
                      thread_id,
                      repository_full_name,
                      subject_type,
                      unsubscribed_at_epoch_ms,
                      ignored_at_epoch_ms
                    )
                    VALUES ('thread-with-ignore-timestamp', 'example-org/example-repo', 'PullRequest', 1, 2)
                    """.trimIndent()
                )
                statement.executeUpdate("PRAGMA user_version = 2")
            }
        }
    }

    private fun readUserVersion(databasePath: String): Long {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    check(resultSet.next())
                    return resultSet.getLong(1)
                }
            }
        }
    }

    private fun readIgnoredColumnNames(databasePath: String): List<String> {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(ignored_notification_threads)").use { resultSet ->
                    val columnNames = mutableListOf<String>()
                    while (resultSet.next()) {
                        columnNames += resultSet.getString("name")
                    }
                    return columnNames
                }
            }
        }
    }

    private fun readIgnoredRows(databasePath: String): List<IgnoredThreadRow> {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT thread_id, repository_full_name, subject_type, ignore_reason, ignored_at_epoch_ms
                    FROM ignored_notification_threads
                    ORDER BY thread_id
                    """.trimIndent(),
                ).use { resultSet ->
                    val rows = mutableListOf<IgnoredThreadRow>()
                    while (resultSet.next()) {
                        rows += IgnoredThreadRow(
                            threadId = resultSet.getString("thread_id"),
                            repositoryFullName = resultSet.getString("repository_full_name"),
                            subjectType = resultSet.getString("subject_type"),
                            ignoreReason = resultSet.getString("ignore_reason"),
                            ignoredAtEpochMs = resultSet.getLong("ignored_at_epoch_ms"),
                        )
                    }
                    return rows
                }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            }
        )
    }
}

private data class IgnoredThreadRow(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val ignoreReason: String,
    val ignoredAtEpochMs: Long,
)
