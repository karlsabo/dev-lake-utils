package com.github.karlsabo.devlake.enghub

import kotlinx.io.files.Path

internal expect fun Path.isSymbolicLink(): Boolean
