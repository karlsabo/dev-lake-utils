package com.github.karlsabo.github.config

import kotlinx.io.files.Path

internal fun platformFilePathIdentity(path: Path): String = platformResolvedFilePath(path).lowercase()

// Case folding safely rejects aliases when a platform cannot reliably report per-volume case sensitivity.
internal expect fun platformResolvedFilePath(path: Path): String

internal expect fun platformValidateRegularFileIfPresent(path: Path)
