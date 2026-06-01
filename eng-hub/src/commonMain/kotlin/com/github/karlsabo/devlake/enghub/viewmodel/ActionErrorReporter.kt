package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupHandle
import kotlinx.coroutines.flow.update

internal class ActionErrorReporter(
    private val state: EngHubViewModelState,
) {
    fun clearActionError() {
        state.actionErrors.update { it.clearCurrent() }
    }

    fun enqueueActionError(message: String) {
        state.actionErrors.update { it.enqueue(message) }
    }

    fun enqueueSetupActionErrorOnce(
        worktreePath: WorktreePath,
        setupHandle: WorktreeSetupHandle,
        message: String,
    ): Boolean {
        val shouldReport = markSetupFailureReported(worktreePath, setupHandle)
        if (shouldReport) enqueueActionError(message)
        return shouldReport
    }

    private fun markSetupFailureReported(
        worktreePath: WorktreePath,
        setupHandle: WorktreeSetupHandle,
    ): Boolean {
        while (true) {
            val currentReports = state.reportedSetupFailureHandlesByPath.value
            val currentHandles = currentReports[worktreePath].orEmpty()
            if (currentHandles.any { it === setupHandle }) return false

            val updatedReports = currentReports + (worktreePath to (currentHandles + setupHandle))
            if (state.reportedSetupFailureHandlesByPath.compareAndSet(currentReports, updatedReports)) {
                return true
            }
        }
    }
}
