package com.github.karlsabo.devlake.enghub.component

internal fun Int.coerceToWorktreeResults(results: List<ExistingWorktreeResult>): Int? {
    if (results.isEmpty()) return null
    return coerceIn(results.indices)
}
