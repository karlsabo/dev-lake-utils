package com.github.karlsabo.devlake.tools

import androidx.compose.ui.window.application
import com.github.karlsabo.devlake.tools.ui.summaryPublisherApp
import kotlinx.io.files.Path

fun main(args: Array<String>) = application {
    val configParameter = args.find { it.startsWith("--config=") }?.substringAfter("=")
    val configFilePath: Path = configParameter?.let { Path(it) } ?: summaryPublisherConfigPath

    summaryPublisherApp(
        configFilePath = configFilePath,
        onExitApplication = ::exitApplication,
    )
}
