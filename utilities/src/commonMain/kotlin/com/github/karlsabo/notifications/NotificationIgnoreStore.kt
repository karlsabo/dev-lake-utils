package com.github.karlsabo.notifications

enum class NotificationIgnoreReason {
    UNSUBSCRIBED,
    DONE,
}

interface NotificationIgnoreStore {
    fun listIgnoredThreadIds(): Set<String>

    fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
    )
}
