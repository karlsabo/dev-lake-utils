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
    fun upgradesVersion1DatabaseAndPreservesUnsubscribedThreadIds() {
        val testDir = Files.createTempDirectory("notification-db-migration-test")
        val databasePath = testDir.resolve("eng-hub-notifications.db")

        try {
            createVersion1Fixture(databasePath.toString())
            assertEquals(1L, readUserVersion(databasePath.toString()))

            val store = SqlDelightNotificationSubscriptionStore(databasePath = databasePath.toString())

            assertEquals(setOf("123456789"), store.listUnsubscribedThreadIds())
            assertEquals(2L, readUserVersion(databasePath.toString()))
            assertEquals(
                listOf(
                    "thread_id",
                    "repository_full_name",
                    "subject_type",
                    "unsubscribed_at_epoch_ms",
                    "ignored_at_epoch_ms",
                ),
                readColumnNames(databasePath.toString()),
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

    private fun readColumnNames(databasePath: String): List<String> {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(unsubscribed_notification_threads)").use { resultSet ->
                    val columnNames = mutableListOf<String>()
                    while (resultSet.next()) {
                        columnNames += resultSet.getString("name")
                    }
                    return columnNames
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
