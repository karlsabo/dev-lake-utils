package com.github.karlsabo.devlake.enghub

import kotlinx.io.files.Path

internal expect fun validateEngHubConfigTransactionPaths(
    primaryPath: Path,
    pendingPath: Path,
    backupPath: Path,
)

internal expect fun replaceEngHubConfigPendingFile(path: Path, content: String)
