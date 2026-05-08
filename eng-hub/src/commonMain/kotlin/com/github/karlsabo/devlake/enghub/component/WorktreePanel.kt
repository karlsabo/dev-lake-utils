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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState

@Composable
fun WorktreePanel(
    localRepositories: List<LocalRepositoryUiState>,
    onAddRepository: () -> Unit,
    onToggleRepository: (String) -> Unit,
    onOpenWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    openingWorktreePaths: Set<String>,
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
                        LocalRepositoryRow(
                            repository = repository,
                            onToggleRepository = { onToggleRepository(repository.path) },
                            onOpenWorktree = onOpenWorktree,
                            openingWorktreePaths = openingWorktreePaths,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalRepositoryRow(
    repository: LocalRepositoryUiState,
    onToggleRepository: () -> Unit,
    onOpenWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    openingWorktreePaths: Set<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleRepository,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = if (repository.isExpanded) {
                                "Collapse ${repository.name}"
                            } else {
                                "Expand ${repository.name}"
                            }
                        },
                ) {
                    Text(
                        text = if (repository.isExpanded) "-" else "+",
                        style = MaterialTheme.typography.button,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = repository.name,
                    style = MaterialTheme.typography.subtitle1,
                )
            }
            Text(text = repository.path, style = MaterialTheme.typography.caption)
            if (repository.isExpanded && repository.worktrees.isNotEmpty()) {
                Spacer(modifier = Modifier.size(8.dp))
                repository.worktrees.forEach { worktree ->
                    val normalizedWorktreePath = worktree.path.normalizedWorktreePath()
                    key(normalizedWorktreePath) {
                        LocalWorktreeRow(
                            worktree = worktree,
                            isOpening = normalizedWorktreePath in openingWorktreePaths,
                            onOpen = { onOpenWorktree(repository.path, worktree.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalWorktreeRow(
    worktree: LocalWorktreeUiState,
    isOpening: Boolean,
    onOpen: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = if (worktree.isDirty) "🟡" else "🟢",
            modifier = Modifier.semantics {
                contentDescription = if (worktree.isDirty) "Dirty worktree" else "Clean worktree"
            },
            style = MaterialTheme.typography.body2,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = worktree.branch,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2,
        )
        if (isOpening) {
            Text(text = "Setting up...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                enabled = !isOpening,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = "Worktree actions for ${worktree.branch}" },
            ) {
                Text(text = "⋮", style = MaterialTheme.typography.button)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    onClick = {
                        menuExpanded = false
                        onOpen()
                    },
                    enabled = !isOpening,
                ) {
                    Text(if (isOpening) "Setting up..." else "Open")
                }
            }
        }
    }
}

private fun String.normalizedWorktreePath(): String = trim().trimEnd('/', '\\')
