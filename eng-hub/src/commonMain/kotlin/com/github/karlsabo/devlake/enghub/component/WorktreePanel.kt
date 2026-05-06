package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState

@Composable
fun WorktreePanel(
    localRepositories: List<LocalRepositoryUiState>,
    onAddRepository: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAddRepository) {
                Text("Add Repository")
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (localRepositories.isEmpty()) {
                Text(
                    text = "No repositories configured",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.body2,
                )
            } else {
                LazyColumn {
                    items(localRepositories, key = { it.path }) { repository ->
                        LocalRepositoryRow(repository = repository)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalRepositoryRow(repository: LocalRepositoryUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = repository.name,
                style = MaterialTheme.typography.subtitle1,
            )
            Text(text = repository.path, style = MaterialTheme.typography.caption)
            if (repository.worktrees.isNotEmpty()) {
                Spacer(modifier = Modifier.size(8.dp))
                repository.worktrees.forEach { worktree ->
                    LocalWorktreeRow(worktree = worktree)
                }
            }
        }
    }
}

@Composable
private fun LocalWorktreeRow(worktree: LocalWorktreeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = worktree.branch, style = MaterialTheme.typography.body2)
    }
}
