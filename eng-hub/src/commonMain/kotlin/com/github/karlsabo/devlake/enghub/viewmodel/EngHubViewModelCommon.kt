package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.git.GitCommandException
import com.github.karlsabo.notifications.IgnoredNotificationThread
import com.github.karlsabo.notifications.NotificationIgnoreStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

internal val logger = KotlinLogging.logger {}

internal fun String.normalizedRepoPath(): String = trim().trimEnd('/', '\\')

internal fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure { failure ->
    if (failure is CancellationException) throw failure
}

internal fun loadIgnoredThreads(
    notificationIgnoreStore: NotificationIgnoreStore,
): Map<String, IgnoredNotificationThread> = runCatching {
    notificationIgnoreStore.listIgnoredThreads().associateBy { it.threadId }
}
    .onFailure { logger.error(it) { "Failed to load persisted ignored notifications" } }
    .getOrElse { emptyMap() }

internal fun checkoutRepoPath(repoFullName: String, config: EngHubConfig): String {
    val repoName = repoFullName.substringAfterLast('/')
    return "${config.repositoriesBaseDir.trimEnd('/')}/$repoName"
}

private val dirtyWorktreeGitOutputMarkers = listOf(
    "contains modified",
    "modified or untracked",
    "contains untracked",
    "dirty",
)

private val dirtyWorktreeMessageMarkers = dirtyWorktreeGitOutputMarkers - "dirty"

internal fun Throwable.isDirtyWorktreeArchiveFailure(): Boolean {
    val commandOutput = causeSequence()
        .filterIsInstance<GitCommandException>()
        .joinToString(separator = "\n") { it.gitOutput }
        .lowercase()
    if (commandOutput.isNotBlank()) return commandOutput.containsAny(dirtyWorktreeGitOutputMarkers)

    return message.orEmpty()
        .lowercase()
        .containsAny(dirtyWorktreeMessageMarkers)
}

private fun Throwable.causeSequence(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeSequence
    while (current != null) {
        yield(current)
        current = current.cause
    }
}

private fun String.containsAny(markers: List<String>): Boolean = markers.any(::contains)
