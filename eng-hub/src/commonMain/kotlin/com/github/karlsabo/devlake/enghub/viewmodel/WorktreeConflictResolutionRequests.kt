package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun enqueueWorktreeConflictResolutionRequest(
    requests: MutableStateFlow<List<WorktreeConflictResolutionRequest>>,
    request: WorktreeConflictResolutionRequest,
) {
    requests.update { current -> if (request in current) current else current + request }
}

internal fun hasWorktreeConflictResolutionRequest(
    requests: MutableStateFlow<List<WorktreeConflictResolutionRequest>>,
    request: WorktreeConflictResolutionRequest,
): Boolean = request in requests.value

internal fun clearWorktreeConflictResolutionRequest(
    requests: MutableStateFlow<List<WorktreeConflictResolutionRequest>>,
    request: WorktreeConflictResolutionRequest,
): Boolean {
    while (true) {
        val current = requests.value
        if (request !in current) return false
        if (requests.compareAndSet(current, current - request)) return true
    }
}
