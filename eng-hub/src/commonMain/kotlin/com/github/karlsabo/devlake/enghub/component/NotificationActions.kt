package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.github.ReviewStateValue

data class NotificationActions(
    val onOpenInBrowser: (String) -> Unit,
    val onCheckoutAndOpen: (repoFullName: String, branch: String) -> Unit,
    val onApprove: (NotificationUiState) -> Unit,
    val onSubmitReview: (NotificationUiState, event: ReviewStateValue, reviewComment: String?) -> Unit,
    val onMarkDone: (NotificationUiState) -> Unit,
    val onUnsubscribe: (NotificationUiState) -> Unit,
)
