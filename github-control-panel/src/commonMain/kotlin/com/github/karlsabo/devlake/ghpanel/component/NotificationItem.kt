package com.github.karlsabo.devlake.ghpanel.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.ghpanel.state.NotificationUiState
import com.github.karlsabo.github.ReviewStateValue

@Composable
fun NotificationItem(
    notification: NotificationUiState,
    onOpenInBrowser: (String) -> Unit,
    onCheckoutAndOpen: (repoFullName: String, branch: String) -> Unit,
    checkoutInProgress: Boolean = false,
    actionInProgress: Boolean = false,
    onApprove: (notificationThreadId: String, apiUrl: String) -> Unit,
    onSubmitReview: (notificationThreadId: String, apiUrl: String, event: ReviewStateValue, reviewComment: String?) -> Unit,
    onMarkDone: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
) {
    var showReviewDialog by remember { mutableStateOf(false) }

    if (showReviewDialog && notification.apiUrl != null) {
        ReviewDialog(
            onSubmit = { event, body ->
                onSubmitReview(notification.notificationThreadId, notification.apiUrl, event, body)
                showReviewDialog = false
            },
            onDismiss = { showReviewDialog = false },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = notification.title, style = MaterialTheme.typography.subtitle1)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = notification.repositoryFullName, style = MaterialTheme.typography.caption)
                    Text(text = notification.reason, style = MaterialTheme.typography.caption)
                    Text(text = notification.subjectType, style = MaterialTheme.typography.caption)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (notification.htmlUrl != null) {
                    Button(
                        onClick = { onOpenInBrowser(notification.htmlUrl) },
                        enabled = !actionInProgress,
                    ) {
                        Text("Open")
                    }
                }

                if (notification.isPullRequest && notification.headRef != null) {
                    Button(
                        onClick = { onCheckoutAndOpen(notification.repositoryFullName, notification.headRef) },
                        enabled = !checkoutInProgress && !actionInProgress,
                    ) {
                        Text(if (checkoutInProgress) "Checking out\u2026" else "Checkout & Open IDEA")
                    }
                }

                if (notification.isPullRequest && notification.apiUrl != null) {
                    Button(
                        onClick = { onApprove(notification.notificationThreadId, notification.apiUrl) },
                        enabled = !actionInProgress,
                    ) {
                        Text("Approve")
                    }
                    Button(
                        onClick = { showReviewDialog = true },
                        enabled = !actionInProgress,
                    ) {
                        Text("Review")
                    }
                }

                Button(
                    onClick = { onMarkDone(notification.notificationThreadId) },
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                ) {
                    Text("Done")
                }
                Button(
                    onClick = { onUnsubscribe(notification.notificationThreadId) },
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                ) {
                    Text("Unsubscribe")
                }
            }
        }
    }
}
