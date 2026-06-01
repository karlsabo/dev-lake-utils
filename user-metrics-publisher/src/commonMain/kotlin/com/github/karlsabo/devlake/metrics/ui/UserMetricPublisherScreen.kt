package com.github.karlsabo.devlake.metrics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.karlsabo.devlake.metrics.ui.components.metricsPreview

@Composable
fun userMetricPublisherScreen(
    metricsPreviewText: String,
    publishButtonText: String,
    publishButtonEnabled: Boolean,
    onPublishClick: () -> Unit,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(
                    onClick = onPublishClick,
                    enabled = publishButtonEnabled,
                ) {
                    Text(publishButtonText)
                }

                metricsPreview(
                    text = metricsPreviewText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
