package com.github.karlsabo.devlake.metrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.karlsabo.devlake.metrics.model.UserMetrics

class UserMetricPublisherState {
    var isLoadingConfig by mutableStateOf(true)
    var isDisplayErrorDialog by mutableStateOf(false)
    var errorMessage by mutableStateOf("")

    var config by mutableStateOf(UserMetricPublisherConfig())
    var dependencies by mutableStateOf<UserMetricPublisherDependencies?>(null)

    var metrics by mutableStateOf<List<UserMetrics>>(emptyList())
    var metricsPreviewText by mutableStateOf("Loading...")

    var publishButtonText by mutableStateOf("Publish to Slack")
    var publishButtonEnabled by mutableStateOf(true)

    fun onPublishStarted() {
        publishButtonEnabled = false
    }

    fun onPublishCompleted(success: Boolean) {
        publishButtonText = if (success) "Message sent!" else "Failed to send message"
    }
}

@Composable
fun rememberUserMetricPublisherState() = remember { UserMetricPublisherState() }
