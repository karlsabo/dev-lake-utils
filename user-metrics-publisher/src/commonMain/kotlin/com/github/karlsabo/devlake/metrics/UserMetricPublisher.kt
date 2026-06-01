package com.github.karlsabo.devlake.metrics

import androidx.compose.ui.window.application
import com.github.karlsabo.devlake.metrics.ui.userMetricPublisherApp

fun main() = application {
    userMetricPublisherApp(onExitApplication = ::exitApplication)
}
