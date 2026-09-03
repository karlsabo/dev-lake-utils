package com.github.karlsabo.devlake.enghub

import kotlinx.io.files.Path
import java.nio.file.Files

internal actual fun Path.isSymbolicLink(): Boolean = Files.isSymbolicLink(java.nio.file.Path.of(toString()))
