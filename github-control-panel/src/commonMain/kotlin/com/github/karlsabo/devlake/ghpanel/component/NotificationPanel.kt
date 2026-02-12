package com.github.karlsabo.devlake.ghpanel.component

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.ghpanel.state.NotificationUiState
import com.github.karlsabo.github.ReviewStateValue

@Composable
fun NotificationPanel(
    notificationsResult: Result<List<NotificationUiState>>,
    onOpenInBrowser: (String) -> Unit,
    onCheckoutAndOpen: (repoFullName: String, branch: String) -> Unit,
    onApprove: (String) -> Unit,
    onSubmitReview: (apiUrl: String, event: ReviewStateValue, reviewComment: String?) -> Unit,
    onMarkDone: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        notificationsResult.fold(
            onSuccess = { notifications ->
                if (notifications.isEmpty()) {
                    Text(
                        text = "No notifications",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.body2,
                    )
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(state = listState) {
                        items(notifications, key = { it.threadId }) { notification ->
                            NotificationItem(
                                notification = notification,
                                onOpenInBrowser = onOpenInBrowser,
                                onCheckoutAndOpen = onCheckoutAndOpen,
                                onApprove = onApprove,
                                onSubmitReview = onSubmitReview,
                                onMarkDone = onMarkDone,
                                onUnsubscribe = onUnsubscribe,
                            )
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(listState),
                    )
                }
            },
            onFailure = { error ->
                Text(
                    text = "Error loading notifications: ${error.message}",
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(8.dp),
                )
            },
        )
    }
}
