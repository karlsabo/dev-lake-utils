package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.github.karlsabo.github.ReviewStateValue
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

private val reviewEventOptions = listOf(
    ReviewStateValue.COMMENTED to "Comment",
    ReviewStateValue.CHANGES_REQUESTED to "Request Changes",
    ReviewStateValue.APPROVED to "Approve",
)

private data class ReviewDialogState(
    val selectedEvent: ReviewStateValue,
    val reviewComment: String,
)

private data class ReviewDialogActions(
    val onSelectedEventChange: (ReviewStateValue) -> Unit,
    val onReviewCommentChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun ReviewDialog(
    onSubmit: (event: ReviewStateValue, reviewComment: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEvent by remember { mutableStateOf(ReviewStateValue.COMMENTED) }
    var reviewComment by remember { mutableStateOf("") }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Submit Review",
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface(modifier = modifier) {
                ReviewDialogContent(
                    state = ReviewDialogState(
                        selectedEvent = selectedEvent,
                        reviewComment = reviewComment,
                    ),
                    actions = ReviewDialogActions(
                        onSelectedEventChange = { selectedEvent = it },
                        onReviewCommentChange = { reviewComment = it },
                        onSubmit = { onSubmit(selectedEvent, reviewComment.ifBlank { null }) },
                        onDismiss = onDismiss,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ReviewDialogContent(
    state: ReviewDialogState,
    actions: ReviewDialogActions,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        Text(text = "Review Type", style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        ReviewEventOptions(
            selectedEvent = state.selectedEvent,
            onSelectedEventChange = actions.onSelectedEventChange,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ReviewCommentField(
            reviewComment = state.reviewComment,
            onReviewCommentChange = actions.onReviewCommentChange,
        )
        Spacer(modifier = Modifier.height(16.dp))
        ReviewDialogButtons(
            selectedEvent = state.selectedEvent,
            reviewComment = state.reviewComment,
            onSubmit = actions.onSubmit,
            onDismiss = actions.onDismiss,
        )
    }
}

@Composable
private fun ReviewEventOptions(
    selectedEvent: ReviewStateValue,
    onSelectedEventChange: (ReviewStateValue) -> Unit,
) {
    reviewEventOptions.forEach { (event, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selectedEvent == event,
                onClick = { onSelectedEventChange(event) },
            )
            Text(text = label)
        }
    }
}

@Composable
private fun ReviewCommentField(
    reviewComment: String,
    onReviewCommentChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = reviewComment,
        onValueChange = onReviewCommentChange,
        label = { Text("Review comment") },
        modifier = Modifier.fillMaxWidth().height(120.dp),
    )
}

@Composable
private fun ReviewDialogButtons(
    selectedEvent: ReviewStateValue,
    reviewComment: String,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row {
        Button(
            onClick = onSubmit,
            enabled = selectedEvent == ReviewStateValue.APPROVED || reviewComment.isNotBlank(),
        ) {
            Text("Submit")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    }
}
