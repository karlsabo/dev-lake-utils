package com.github.karlsabo.devlake.enghub.component

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
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.git.WorktreeSetupStatus
@Composable
fun NotificationItem(
    notification: NotificationUiState,
    actions: NotificationActions,
    setupStatus: WorktreeSetupStatus?,
    modifier: Modifier = Modifier,
    actionInProgress: Boolean = false,
) {
    var showReviewDialog by remember { mutableStateOf(false) }

    NotificationReviewDialog(
        visible = showReviewDialog && notification.apiUrl != null,
        notification = notification,
        actions = actions,
        onDismiss = { showReviewDialog = false },
    )

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotificationDetails(
                notification = notification,
                modifier = Modifier.weight(1f),
            )
            NotificationActionButtons(
                notification = notification,
                actions = actions,
                setupStatus = setupStatus,
                actionInProgress = actionInProgress,
                onShowReviewDialog = { showReviewDialog = true },
            )
        }
    }
}

@Composable
private fun NotificationReviewDialog(
    visible: Boolean,
    notification: NotificationUiState,
    actions: NotificationActions,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    ReviewDialog(
        onSubmit = { event, body ->
            actions.onSubmitReview(notification, event, body)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun NotificationDetails(
    notification: NotificationUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = notification.displayTitle, style = MaterialTheme.typography.subtitle1)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = notification.repositoryFullName, style = MaterialTheme.typography.caption)
            Text(text = notification.reason, style = MaterialTheme.typography.caption)
            Text(text = notification.subjectType, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun NotificationActionButtons(
    notification: NotificationUiState,
    actions: NotificationActions,
    setupStatus: WorktreeSetupStatus?,
    actionInProgress: Boolean,
    onShowReviewDialog: () -> Unit,
) {
    val setupInProgress = setupStatus != null

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NotificationOpenButton(notification, actions, actionInProgress)
        NotificationSetupButton(notification, actions, setupStatus, setupInProgress, actionInProgress)
        NotificationReviewButtons(notification, actions, actionInProgress, onShowReviewDialog)
        NotificationDoneButton(notification, actions, actionInProgress)
        NotificationUnsubscribeButton(notification, actions, actionInProgress)
    }
}

@Composable
private fun NotificationOpenButton(
    notification: NotificationUiState,
    actions: NotificationActions,
    actionInProgress: Boolean,
) {
    notification.htmlUrl?.let { htmlUrl ->
        Button(
            onClick = { actions.onOpenInBrowser(htmlUrl) },
            enabled = !actionInProgress,
        ) {
            Text("Open")
        }
    }
}

@Composable
private fun NotificationSetupButton(
    notification: NotificationUiState,
    actions: NotificationActions,
    setupStatus: WorktreeSetupStatus?,
    setupInProgress: Boolean,
    actionInProgress: Boolean,
) {
    val headRef = notification.headRef
    if (!notification.isPullRequest || headRef == null) return

    Button(
        onClick = { actions.onCheckoutAndOpen(notification.repositoryFullName, headRef) },
        enabled = !setupInProgress && !actionInProgress,
    ) {
        Text(setupActionLabel(defaultLabel = "Setup", setupStatus = setupStatus))
    }
}

@Composable
private fun NotificationReviewButtons(
    notification: NotificationUiState,
    actions: NotificationActions,
    actionInProgress: Boolean,
    onShowReviewDialog: () -> Unit,
) {
    if (!notification.isPullRequest || notification.apiUrl == null) return

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = { actions.onApprove(notification) },
            enabled = !actionInProgress,
        ) {
            Text("Approve")
        }
        Button(
            onClick = onShowReviewDialog,
            enabled = !actionInProgress,
        ) {
            Text("Review")
        }
    }
}

@Composable
private fun NotificationDoneButton(
    notification: NotificationUiState,
    actions: NotificationActions,
    actionInProgress: Boolean,
) {
    Button(
        onClick = { actions.onMarkDone(notification) },
        enabled = !actionInProgress,
        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
    ) {
        Text("Done")
    }
}

@Composable
private fun NotificationUnsubscribeButton(
    notification: NotificationUiState,
    actions: NotificationActions,
    actionInProgress: Boolean,
) {
    Button(
        onClick = { actions.onUnsubscribe(notification) },
        enabled = !actionInProgress,
        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
    ) {
        Text("Unsubscribe")
    }
}
