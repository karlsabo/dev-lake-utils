package com.github.karlsabo.notifications

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TEST_DATABASE_FILE_NAME = "eng-hub-notifications.db"
private const val VERSION_1_THREAD_ID = "123456789"
private const val VERSION_2_THREAD_ID = "thread-with-ignore-timestamp"
private const val VERSION_3_THREAD_ID = "thread-done-without-watermark"
private const val REPOSITORY_FULL_NAME = "example-org/example-repo"
private const val SUBJECT_TYPE = "PullRequest"
private const val UNSUBSCRIBED_REASON = "UNSUBSCRIBED"
private const val DONE_REASON = "DONE"
private const val SCHEMA_VERSION_1 = 1
private const val SCHEMA_VERSION_2 = 2
private const val SCHEMA_VERSION_3 = 3
private const val VERSION_1_UNSUBSCRIBED_AT_EPOCH_MS = 1L
private const val VERSION_2_UNSUBSCRIBED_AT_EPOCH_MS = 1L
private const val VERSION_2_IGNORED_AT_EPOCH_MS = 2L
private const val VERSION_3_IGNORED_AT_EPOCH_MS = 3L
private const val USER_VERSION_COLUMN_INDEX = 1

class NotificationDatabaseMigrationTest {

    @Test
    fun upgradesVersion1DatabaseAndPreservesUnsubscribedRowsAsIgnoredRows() {
        val testDir = Files.createTempDirectory("notification-db-migration-test")
        val databasePath = testDir.resolve(TEST_DATABASE_FILE_NAME)

        try {
            createVersion1Fixture(databasePath.toString())
            assertEquals(SCHEMA_VERSION_1.toLong(), readUserVersion(databasePath.toString()))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf(VERSION_1_THREAD_ID), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath.toString()))
            assertEquals(
                listOf(
                    "thread_id",
                    "repository_full_name",
                    "subject_type",
                    "ignore_reason",
                    "ignored_at_epoch_ms",
                    "notification_updated_at_epoch_ms",
                ),
                readIgnoredColumnNames(databasePath.toString()),
            )
            assertEquals(
                listOf(
                    IgnoredThreadRow(
                        VERSION_1_THREAD_ID,
                        REPOSITORY_FULL_NAME,
                        SUBJECT_TYPE,
                        UNSUBSCRIBED_REASON,
                        VERSION_1_UNSUBSCRIBED_AT_EPOCH_MS,
                        null,
                    ),
                ),
                readIgnoredRows(databasePath.toString()),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    @Test
    fun upgradesVersion2DatabaseAndPreservesIgnoredTimestampWhenPresent() {
        val testDir = Files.createTempDirectory("notification-db-migration-test")
        val databasePath = testDir.resolve(TEST_DATABASE_FILE_NAME)

        try {
            createVersion2Fixture(databasePath.toString())
            assertEquals(SCHEMA_VERSION_2.toLong(), readUserVersion(databasePath.toString()))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf(VERSION_2_THREAD_ID), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath.toString()))
            assertEquals(
                listOf(
                    IgnoredThreadRow(
                        VERSION_2_THREAD_ID,
                        REPOSITORY_FULL_NAME,
                        SUBJECT_TYPE,
                        UNSUBSCRIBED_REASON,
                        VERSION_2_IGNORED_AT_EPOCH_MS,
                        null,
                    ),
                ),
                readIgnoredRows(databasePath.toString()),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    @Test
    fun upgradesVersion3DatabaseAndAddsNullableNotificationUpdatedAtWatermark() {
        val testDir = Files.createTempDirectory("notification-db-migration-test")
        val databasePath = testDir.resolve(TEST_DATABASE_FILE_NAME)

        try {
            createVersion3Fixture(databasePath.toString())
            assertEquals(SCHEMA_VERSION_3.toLong(), readUserVersion(databasePath.toString()))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(
                listOf(
                    IgnoredNotificationThread(
                        threadId = VERSION_3_THREAD_ID,
                        repositoryFullName = REPOSITORY_FULL_NAME,
                        subjectType = SUBJECT_TYPE,
                        reason = NotificationIgnoreReason.DONE,
                        ignoredAtEpochMs = VERSION_3_IGNORED_AT_EPOCH_MS,
                        notificationUpdatedAtEpochMs = null,
                    ),
                ),
                store.listIgnoredThreads(),
            )
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath.toString()))
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
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO unsubscribed_notification_threads(
                      thread_id,
                      repository_full_name,
                      subject_type,
                      unsubscribed_at_epoch_ms
                    )
                    VALUES (
                      '$VERSION_1_THREAD_ID',
                      '$REPOSITORY_FULL_NAME',
                      '$SUBJECT_TYPE',
                      $VERSION_1_UNSUBSCRIBED_AT_EPOCH_MS
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION_1")
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
                    """.trimIndent(),
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
                    VALUES (
                      '$VERSION_2_THREAD_ID',
                      '$REPOSITORY_FULL_NAME',
                      '$SUBJECT_TYPE',
                      $VERSION_2_UNSUBSCRIBED_AT_EPOCH_MS,
                      $VERSION_2_IGNORED_AT_EPOCH_MS
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION_2")
            }
        }
    }

    private fun createVersion3Fixture(databasePath: String) {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE ignored_notification_threads (
                      thread_id TEXT NOT NULL PRIMARY KEY,
                      repository_full_name TEXT NOT NULL,
                      subject_type TEXT NOT NULL,
                      ignore_reason TEXT NOT NULL,
                      ignored_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO ignored_notification_threads(
                      thread_id,
                      repository_full_name,
                      subject_type,
                      ignore_reason,
                      ignored_at_epoch_ms
                    )
                    VALUES (
                      '$VERSION_3_THREAD_ID',
                      '$REPOSITORY_FULL_NAME',
                      '$SUBJECT_TYPE',
                      '$DONE_REASON',
                      $VERSION_3_IGNORED_AT_EPOCH_MS
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION_3")
            }
        }
    }

    private fun readUserVersion(databasePath: String): Long = queryDatabase(
        databasePath = databasePath,
        sql = "PRAGMA user_version",
    ) { resultSet ->
        check(resultSet.next())
        resultSet.getLong(USER_VERSION_COLUMN_INDEX)
    }

    private fun readIgnoredColumnNames(databasePath: String): List<String> = queryDatabase(
        databasePath = databasePath,
        sql = "PRAGMA table_info(ignored_notification_threads)",
    ) { resultSet ->
        val columnNames = mutableListOf<String>()
        while (resultSet.next()) {
            columnNames += resultSet.getString("name")
        }
        columnNames
    }

    private fun readIgnoredRows(databasePath: String): List<IgnoredThreadRow> = queryDatabase(
        databasePath = databasePath,
        sql = """
            SELECT
              thread_id,
              repository_full_name,
              subject_type,
              ignore_reason,
              ignored_at_epoch_ms,
              notification_updated_at_epoch_ms
            FROM ignored_notification_threads
            ORDER BY thread_id
        """.trimIndent(),
    ) { resultSet ->
        val rows = mutableListOf<IgnoredThreadRow>()
        while (resultSet.next()) {
            rows += resultSet.toIgnoredThreadRow()
        }
        rows
    }

    private fun <T> queryDatabase(
        databasePath: String,
        sql: String,
        readResult: (ResultSet) -> T,
    ): T = DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use(readResult)
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }
}

private data class IgnoredThreadRow(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val ignoreReason: String,
    val ignoredAtEpochMs: Long,
    val notificationUpdatedAtEpochMs: Long?,
)

private fun ResultSet.toIgnoredThreadRow(): IgnoredThreadRow = IgnoredThreadRow(
    threadId = getString("thread_id"),
    repositoryFullName = getString("repository_full_name"),
    subjectType = getString("subject_type"),
    ignoreReason = getString("ignore_reason"),
    ignoredAtEpochMs = getLong("ignored_at_epoch_ms"),
    notificationUpdatedAtEpochMs = getNullableLong("notification_updated_at_epoch_ms"),
)

private fun ResultSet.getNullableLong(columnLabel: String): Long? {
    val value = getLong(columnLabel)
    return if (wasNull()) null else value
}
