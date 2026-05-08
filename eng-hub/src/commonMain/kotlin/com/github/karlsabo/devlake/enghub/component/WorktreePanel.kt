package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.window.DialogWindow
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

internal enum class WorktreeMenuAction {
    Open,
    Archive,
}

internal fun visibleWorktreeMenuActions(worktree: LocalWorktreeUiState): List<WorktreeMenuAction> {
    return buildList {
        add(WorktreeMenuAction.Open)
        if (!worktree.isRoot) add(WorktreeMenuAction.Archive)
    }
}

@Composable
fun WorktreePanel(
    localRepositories: List<LocalRepositoryUiState>,
    onAddRepository: () -> Unit,
    onToggleRepository: (String) -> Unit,
    onOpenWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    onArchiveWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    openingWorktreePaths: Set<String>,
    archivingWorktreePaths: Set<String>,
    modifier: Modifier = Modifier,
) {
    var pendingArchive by remember { mutableStateOf<PendingArchive?>(null) }

    pendingArchive?.let { archive ->
        ArchiveWorktreeDialog(
            worktreePath = archive.worktreePath,
            onConfirm = {
                pendingArchive = null
                onArchiveWorktree(archive.repoRootPath, archive.worktreePath)
            },
            onDismiss = { pendingArchive = null },
        )
    }

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
                            onArchiveWorktree = { worktreePath ->
                                pendingArchive = PendingArchive(repository.path, worktreePath)
                            },
                            openingWorktreePaths = openingWorktreePaths,
                            archivingWorktreePaths = archivingWorktreePaths,
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
    onArchiveWorktree: (worktreePath: String) -> Unit,
    openingWorktreePaths: Set<String>,
    archivingWorktreePaths: Set<String>,
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
                            isArchiving = normalizedWorktreePath in archivingWorktreePaths,
                            onOpen = { onOpenWorktree(repository.path, worktree.path) },
                            onArchive = { onArchiveWorktree(worktree.path) },
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
    isArchiving: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isBusy = isOpening || isArchiving

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
        if (isArchiving) {
            Text(text = "Archiving...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                enabled = !isBusy,
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
                visibleWorktreeMenuActions(worktree).forEach { action ->
                    when (action) {
                        WorktreeMenuAction.Open -> DropdownMenuItem(
                            onClick = {
                                menuExpanded = false
                                onOpen()
                            },
                            enabled = !isBusy,
                        ) {
                            Text(if (isOpening) "Setting up..." else "Open")
                        }

                        WorktreeMenuAction.Archive -> DropdownMenuItem(
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            },
                            enabled = !isBusy,
                        ) {
                            Text("Archive")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveWorktreeDialog(
    worktreePath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Archive Worktree",
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                ) {
                    Text(text = "Archive Worktree", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Remove this worktree and delete any leftover checkout directory?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = worktreePath, style = MaterialTheme.typography.caption)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = onConfirm) {
                            Text("Archive")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

private data class PendingArchive(
    val repoRootPath: String,
    val worktreePath: String,
)

private fun String.normalizedWorktreePath(): String = trim().trimEnd('/', '\\')
