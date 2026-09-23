package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveBinEntry
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import kotlinx.coroutines.delay

private const val COUNTDOWN_REFRESH_INTERVAL_MS = 1_000L

internal data class WorktreeMutationScreenState(
    val archivingPaths: Set<String>,
    val archiveBinEntries: List<WorktreeArchiveBinEntry>,
    val queuedArchivePaths: Set<String>,
    val updatingPaths: Set<String>,
    val rebasingPaths: Set<String>,
    val mergingPaths: Set<String>,
)

@Composable
internal fun collectWorktreeMutationScreenState(viewModel: EngHubViewModel): WorktreeMutationScreenState {
    val archivingPaths by viewModel.archivingLocalWorktreePathsStateFlow.collectAsState()
    val queuedArchives by viewModel.queuedWorktreeArchivesStateFlow.collectAsState()
    val updatingPaths by viewModel.updatingLocalWorktreePathsStateFlow.collectAsState()
    val rebasingPaths by viewModel.rebasingLocalWorktreePathsStateFlow.collectAsState()
    val mergingPaths by viewModel.mergingLocalWorktreePathsStateFlow.collectAsState()
    val archiveBinEntries = collectArchiveBinEntries(
        queuedArchives = queuedArchives,
        nowEpochMs = viewModel.currentArchiveTimeEpochMs,
    )
    return WorktreeMutationScreenState(
        archivingPaths = archivingPaths,
        archiveBinEntries = archiveBinEntries,
        queuedArchivePaths = queuedArchives.mapTo(mutableSetOf(), WorktreeArchiveJob::worktreePath),
        updatingPaths = updatingPaths,
        rebasingPaths = rebasingPaths,
        mergingPaths = mergingPaths,
    )
}

@Composable
internal fun collectArchiveBinEntries(
    queuedArchives: List<WorktreeArchiveJob>,
    nowEpochMs: () -> Long,
): List<WorktreeArchiveBinEntry> {
    val currentNowEpochMs by rememberUpdatedState(nowEpochMs)
    val observedNowEpochMs by produceState(currentNowEpochMs(), queuedArchives) {
        value = currentNowEpochMs()
        while (queuedArchives.isNotEmpty()) {
            delay(COUNTDOWN_REFRESH_INTERVAL_MS)
            value = currentNowEpochMs()
        }
    }
    return queuedArchives.map { it.toArchiveBinEntry(observedNowEpochMs) }
}

private fun WorktreeArchiveJob.toArchiveBinEntry(nowEpochMs: Long): WorktreeArchiveBinEntry {
    val repositoryName = repositoryRootPath
        .trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
    return WorktreeArchiveBinEntry(
        repository = repositoryName,
        branch = branch,
        remainingSeconds = ((deadlineAtEpochMs - nowEpochMs).coerceAtLeast(0) + 999) / 1_000,
    )
}
