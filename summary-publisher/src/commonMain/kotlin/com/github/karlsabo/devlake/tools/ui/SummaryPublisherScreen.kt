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

internal data class SummaryPublisherPublishState(
    val buttonText: String,
    val isButtonEnabled: Boolean,
    val isLoadingSummary: Boolean,
    val isSendingSlackMessage: Boolean,
)

internal data class SummaryPublisherScreenState(
    val topLevelSummary: String,
    val projectSummaries: List<ProjectSummaryHolder>,
    val publishState: SummaryPublisherPublishState,
)

internal data class SummaryPublisherScreenActions(
    val onTopLevelSummaryChange: (String) -> Unit,
    val onProjectMessageChange: (Int, String) -> Unit,
    val onProjectDelete: (Int) -> Unit,
    val onPublishClick: () -> Unit,
)

@Composable
internal fun SummaryPublisherScreen(
    state: SummaryPublisherScreenState,
    actions: SummaryPublisherScreenActions,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                LoadingButton(
                    text = state.publishState.buttonText,
                    isLoading = state.publishState.isSendingSlackMessage,
                    enabled = state.publishState.isButtonEnabled && !state.publishState.isLoadingSummary,
                    onClick = actions.onPublishClick,
                )

                SummaryTextField(
                    value = state.topLevelSummary,
                    onValueChange = actions.onTopLevelSummaryChange,
                    modifier = Modifier.padding(8.dp),
                )

                ProjectSummaryList(
                    summaries = state.projectSummaries,
                    onMessageChange = actions.onProjectMessageChange,
                    onDelete = actions.onProjectDelete,
                    modifier = Modifier.weight(1f).padding(8.dp),
                )
            }
        }
    }
}
