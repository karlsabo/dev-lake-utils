package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.git.WorktreeSetupStatus

internal fun NotificationUiState.checkoutSetupStatus(
    setupStatusFor: (repoFullName: String, branch: String) -> WorktreeSetupStatus?,
): WorktreeSetupStatus? = headRef?.let { setupStatusFor(repositoryFullName, it) }

@Composable
fun notificationPanel(
    notificationsResult: Result<List<NotificationUiState>>?,
    actions: NotificationActions,
    setupStatusFor: (repoFullName: String, branch: String) -> WorktreeSetupStatus?,
    actingOnThreadIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (notificationsResult == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }
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
                        items(notifications, key = { it.notificationThreadId }) { notification ->
                            notificationItem(
                                notification = notification,
                                actions = actions,
                                setupStatus = notification.checkoutSetupStatus(setupStatusFor),
                                actionInProgress = notification.notificationThreadId in actingOnThreadIds,
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
