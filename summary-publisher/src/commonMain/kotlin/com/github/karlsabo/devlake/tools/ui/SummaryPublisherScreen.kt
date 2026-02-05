package com.github.karlsabo.devlake.tools.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.tools.ProjectSummaryHolder
import com.github.karlsabo.devlake.tools.ui.components.LoadingButton
import com.github.karlsabo.devlake.tools.ui.components.ProjectSummaryList
import com.github.karlsabo.devlake.tools.ui.components.SummaryTextField

@Composable
fun SummaryPublisherScreen(
    topLevelSummary: String,
    onTopLevelSummaryChange: (String) -> Unit,
    projectSummaries: List<ProjectSummaryHolder>,
    onProjectMessageChange: (Int, String) -> Unit,
    onProjectDelete: (Int) -> Unit,
    publishButtonText: String,
    publishButtonEnabled: Boolean,
    isLoadingSummary: Boolean,
    isSendingSlackMessage: Boolean,
    onPublishClick: () -> Unit,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                LoadingButton(
                    text = publishButtonText,
                    isLoading = isSendingSlackMessage,
                    enabled = publishButtonEnabled && !isLoadingSummary,
                    onClick = onPublishClick
                )

                SummaryTextField(
                    value = topLevelSummary,
                    onValueChange = onTopLevelSummaryChange,
                    modifier = Modifier.padding(8.dp)
                )

                ProjectSummaryList(
                    summaries = projectSummaries,
                    onMessageChange = onProjectMessageChange,
                    onDelete = onProjectDelete,
                    modifier = Modifier.weight(1f).padding(8.dp)
                )
            }
        }
    }
}
