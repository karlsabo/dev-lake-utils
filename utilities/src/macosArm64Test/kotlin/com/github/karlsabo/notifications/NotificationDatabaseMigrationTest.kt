package com.github.karlsabo.notifications

import co.touchlab.sqliter.Cursor
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
private const val TABLE_INFO_NAME_COLUMN_INDEX = 1
private const val THREAD_ID_COLUMN_INDEX = 0
private const val REPOSITORY_FULL_NAME_COLUMN_INDEX = 1
private const val SUBJECT_TYPE_COLUMN_INDEX = 2
private const val IGNORE_REASON_COLUMN_INDEX = 3
private const val IGNORED_AT_EPOCH_MS_COLUMN_INDEX = 4
private const val NOTIFICATION_UPDATED_AT_EPOCH_MS_COLUMN_INDEX = 5

class NotificationDatabaseMigrationTest {

    @Test
    fun upgradesVersion1DatabaseAndPreservesUnsubscribedRowsAsIgnoredRows() {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, TEST_DATABASE_FILE_NAME)

        try {
            createSchemaVersion1Fixture(databasePath)
            assertEquals(SCHEMA_VERSION_1.toLong(), readUserVersion(databasePath))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf(VERSION_1_THREAD_ID), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath))
            assertEquals(
                listOf(
                    "thread_id",
                    "repository_full_name",
                    "subject_type",
                    "ignore_reason",
                    "ignored_at_epoch_ms",
                    "notification_updated_at_epoch_ms",
                ),
                readIgnoredColumnNames(databasePath),
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
                readIgnoredRows(databasePath),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    @Test
    fun upgradesVersion2DatabaseAndPreservesIgnoredTimestampWhenPresent() {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, TEST_DATABASE_FILE_NAME)

        try {
            createSchemaVersion2Fixture(databasePath)
            assertEquals(SCHEMA_VERSION_2.toLong(), readUserVersion(databasePath))

            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath.toString())

            assertEquals(setOf(VERSION_2_THREAD_ID), store.listIgnoredThreadIds())
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath))
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
                readIgnoredRows(databasePath),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }

    @Test
    fun upgradesVersion3DatabaseAndAddsNullableNotificationUpdatedAtWatermark() {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, TEST_DATABASE_FILE_NAME)

        try {
            createSchemaVersion3Fixture(databasePath)
            assertEquals(SCHEMA_VERSION_3.toLong(), readUserVersion(databasePath))

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
            assertEquals(NotificationDatabase.Schema.version, readUserVersion(databasePath))
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
                VALUES (
                  '$VERSION_1_THREAD_ID',
                  '$REPOSITORY_FULL_NAME',
                  '$SUBJECT_TYPE',
                  $VERSION_1_UNSUBSCRIBED_AT_EPOCH_MS
                )
                """.trimIndent(),
            )
            connection.setVersion(SCHEMA_VERSION_1)
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
                VALUES (
                  '$VERSION_2_THREAD_ID',
                  '$REPOSITORY_FULL_NAME',
                  '$SUBJECT_TYPE',
                  $VERSION_2_UNSUBSCRIBED_AT_EPOCH_MS,
                  $VERSION_2_IGNORED_AT_EPOCH_MS
                )
                """.trimIndent(),
            )
            connection.setVersion(SCHEMA_VERSION_2)
        }
    }

    private fun createSchemaVersion3Fixture(databasePath: Path) {
        withDatabaseConnection(databasePath) { connection ->
            connection.rawExecSql(
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
            connection.rawExecSql(
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
            connection.setVersion(SCHEMA_VERSION_3)
        }
    }

    private fun readUserVersion(databasePath: Path): Long = withDatabaseConnection(databasePath) { connection ->
        connection.getVersion().toLong()
    }

    private fun readIgnoredColumnNames(databasePath: Path): List<String> = withDatabaseConnection(databasePath) { connection ->
        connection.withStatement("PRAGMA table_info(ignored_notification_threads)") {
            val cursor = query()
            val columnNames = mutableListOf<String>()
            try {
                while (cursor.next()) {
                    columnNames += cursor.getString(TABLE_INFO_NAME_COLUMN_INDEX)
                }
                columnNames
            } finally {
                resetStatement()
            }
        }
    }

    private fun readIgnoredRows(databasePath: Path): List<IgnoredThreadRow> =
        withDatabaseConnection(databasePath) { connection ->
        connection.withStatement(
            """
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
        ) {
            val cursor = query()
            val rows = mutableListOf<IgnoredThreadRow>()
            try {
                while (cursor.next()) {
                    rows += IgnoredThreadRow(
                        threadId = cursor.getString(THREAD_ID_COLUMN_INDEX),
                        repositoryFullName = cursor.getString(REPOSITORY_FULL_NAME_COLUMN_INDEX),
                        subjectType = cursor.getString(SUBJECT_TYPE_COLUMN_INDEX),
                        ignoreReason = cursor.getString(IGNORE_REASON_COLUMN_INDEX),
                        ignoredAtEpochMs = cursor.getLong(IGNORED_AT_EPOCH_MS_COLUMN_INDEX),
                        notificationUpdatedAtEpochMs = cursor.getNullableLong(
                            NOTIFICATION_UPDATED_AT_EPOCH_MS_COLUMN_INDEX,
                        ),
                    )
                }
                rows
            } finally {
                resetStatement()
            }
        }
    }
}

private fun <T> withDatabaseConnection(
    databasePath: Path,
    block: (co.touchlab.sqliter.DatabaseConnection) -> T,
): T {
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
    val notificationUpdatedAtEpochMs: Long?,
)

private fun Cursor.getNullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
