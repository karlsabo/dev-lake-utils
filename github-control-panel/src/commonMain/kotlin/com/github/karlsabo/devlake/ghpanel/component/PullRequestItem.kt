package com.github.karlsabo.devlake.ghpanel.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.ghpanel.state.PullRequestUiState

@Composable
fun PullRequestItem(
    pr: PullRequestUiState,
    onOpenInBrowser: (String) -> Unit,
    onCheckoutAndOpen: (repoFullName: String, branch: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${pr.number} ${pr.title}",
                        style = MaterialTheme.typography.subtitle1,
                    )
                    if (pr.isDraft) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Draft", style = MaterialTheme.typography.caption)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = pr.repositoryFullName, style = MaterialTheme.typography.caption)
                    StatusBadge(status = pr.ciStatus)
                    Text(text = pr.ciSummaryText, style = MaterialTheme.typography.caption)
                    Text(text = pr.reviewSummaryText, style = MaterialTheme.typography.caption)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpenInBrowser(pr.htmlUrl) }) {
                    Text("Open in Browser")
                }
                if (pr.headRef != null) {
                    Button(onClick = { onCheckoutAndOpen(pr.repositoryFullName, pr.headRef) }) {
                        Text("Checkout & Open IDEA")
                    }
                }
            }
        }
    }
}
