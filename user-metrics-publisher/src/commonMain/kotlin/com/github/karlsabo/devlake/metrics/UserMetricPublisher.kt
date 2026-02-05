package com.github.karlsabo.devlake.metrics

import androidx.compose.ui.window.application
import com.github.karlsabo.devlake.metrics.ui.UserMetricPublisherApp

fun main() = application {
    UserMetricPublisherApp(onExitApplication = ::exitApplication)
}
