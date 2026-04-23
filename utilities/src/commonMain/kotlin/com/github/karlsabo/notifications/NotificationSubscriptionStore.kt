package com.github.karlsabo.notifications

interface NotificationSubscriptionStore {
    fun listUnsubscribedThreadIds(): Set<String>

    fun saveUnsubscribedThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        unsubscribedAtEpochMs: Long,
    )
}
