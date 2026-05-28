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
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeBranchNameValidationResult
import com.github.karlsabo.git.WorktreeBranchNameValidator
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

internal enum class WorktreeMenuAction {
    Open,
    CreateWorktree,
    Archive,
}

internal data class PendingCreateWorktree(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String = "",
)

internal fun visibleWorktreeMenuActions(worktree: LocalWorktreeUiState): List<WorktreeMenuAction> {
    return buildList {
        add(WorktreeMenuAction.Open)
        add(WorktreeMenuAction.CreateWorktree)
        if (!worktree.isRoot) add(WorktreeMenuAction.Archive)
    }
}

internal fun createWorktreeDialogState(
    repoRootPath: String,
    worktree: LocalWorktreeUiState,
): PendingCreateWorktree = PendingCreateWorktree(
    repoRootPath = repoRootPath,
    baseWorktreePath = worktree.path,
    baseBranch = worktree.branch,
)

internal fun isWorktreeCreateEnabled(
    worktree: LocalWorktreeUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
): Boolean = setupStatus == null && !isArchiving && !worktree.isDetachedDisplayBranch()

internal fun isWorktreeArchiveEnabled(setupStatus: WorktreeSetupStatus?, isArchiving: Boolean): Boolean =
    setupStatus == null && !isArchiving

internal data class CreateWorktreeTargetBranchValidation(
    val result: WorktreeBranchNameValidationResult,
    val isCheckingGitRefFormat: Boolean,
    val targetBranchMatchesBase: Boolean = false,
)

private const val TARGET_BRANCH_MATCHES_BASE_MESSAGE = "Target branch must differ from the base branch"

internal fun startCreateWorktreeTargetBranchValidation(
    baseBranch: String,
    targetBranch: String,
    branchNameValidator: WorktreeBranchNameValidator,
): CreateWorktreeTargetBranchValidation {
    val localValidation = branchNameValidator.validateWithoutGitRefFormatCheck(targetBranch)
    val targetBranchMatchesBase = targetBranchMatchesBase(baseBranch, targetBranch)
    return CreateWorktreeTargetBranchValidation(
        result = localValidation,
        isCheckingGitRefFormat = localValidation.isValid && !targetBranchMatchesBase,
        targetBranchMatchesBase = targetBranchMatchesBase,
    )
}

internal fun finishCreateWorktreeTargetBranchValidation(
    baseBranch: String,
    targetBranch: String,
    branchNameValidator: WorktreeBranchNameValidator,
): CreateWorktreeTargetBranchValidation {
    val localValidation = branchNameValidator.validateWithoutGitRefFormatCheck(targetBranch)
    val targetBranchMatchesBase = targetBranchMatchesBase(baseBranch, targetBranch)
    return CreateWorktreeTargetBranchValidation(
        result = if (localValidation.isValid && !targetBranchMatchesBase) {
            branchNameValidator.validate(targetBranch)
        } else {
            localValidation
        },
        isCheckingGitRefFormat = false,
        targetBranchMatchesBase = targetBranchMatchesBase,
    )
}

internal fun createWorktreeTargetBranchValidationMessage(
    targetBranch: String,
    validation: CreateWorktreeTargetBranchValidation,
): String? = when {
    targetBranch.isEmpty() -> null
    validation.isCheckingGitRefFormat -> null
    validation.targetBranchMatchesBase -> TARGET_BRANCH_MATCHES_BASE_MESSAGE
    else -> validation.result.message
}

internal fun isCreateWorktreeConfirmEnabled(validation: CreateWorktreeTargetBranchValidation): Boolean =
    !validation.isCheckingGitRefFormat && validation.result.isValid && !validation.targetBranchMatchesBase

private fun targetBranchMatchesBase(baseBranch: String, targetBranch: String): Boolean =
    targetBranch.isNotEmpty() && targetBranch == baseBranch

@Composable
fun WorktreePanel(
    localRepositories: List<LocalRepositoryUiState>,
    onAddRepository: () -> Unit,
    onToggleRepository: (String) -> Unit,
    onOpenWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    onArchiveWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    forceArchiveRequest: ForceArchiveWorktreeUiState?,
    onConfirmForceArchiveWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    onDismissForceArchiveWorktree: () -> Unit,
    setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
    archivingWorktreePaths: Set<String>,
    modifier: Modifier = Modifier,
) {
    var pendingArchive by remember { mutableStateOf<PendingArchive?>(null) }
    var pendingCreateWorktree by remember { mutableStateOf<PendingCreateWorktree?>(null) }

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

    forceArchiveRequest?.let { archive ->
        ForceArchiveWorktreeDialog(
            worktreePath = archive.worktreePath,
            onConfirm = {
                onConfirmForceArchiveWorktree(archive.repoRootPath, archive.worktreePath)
            },
            onDismiss = onDismissForceArchiveWorktree,
        )
    }

    pendingCreateWorktree?.let { createWorktree ->
        CreateWorktreeDialog(
            state = createWorktree,
            onTargetBranchChange = { targetBranch ->
                pendingCreateWorktree = pendingCreateWorktree?.copy(targetBranch = targetBranch)
            },
            onDismiss = { pendingCreateWorktree = null },
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
                            onOpenCreateWorktreeDialog = { worktree ->
                                pendingCreateWorktree = createWorktreeDialogState(repository.path, worktree)
                            },
                            setupStatuses = setupStatuses,
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
    onOpenCreateWorktreeDialog: (LocalWorktreeUiState) -> Unit,
    setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
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
                    val worktreeSetupStatus = setupStatuses[WorktreePath(normalizedWorktreePath)]
                    key(normalizedWorktreePath) {
                        LocalWorktreeRow(
                            worktree = worktree,
                            setupStatus = worktreeSetupStatus,
                            isArchiving = normalizedWorktreePath in archivingWorktreePaths,
                            onOpen = { onOpenWorktree(repository.path, worktree.path) },
                            onArchive = { onArchiveWorktree(worktree.path) },
                            onOpenCreateWorktreeDialog = { onOpenCreateWorktreeDialog(worktree) },
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
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onOpenCreateWorktreeDialog: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val setupInProgress = setupStatus != null
    val openEnabled = !setupInProgress && !isArchiving
    val createEnabled = isWorktreeCreateEnabled(worktree, setupStatus, isArchiving)
    val archiveEnabled = isWorktreeArchiveEnabled(setupStatus, isArchiving)

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
        setupStatus?.let {
            Text(text = it.setupStatusLabel(), style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isArchiving) {
            Text(text = "Archiving...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                enabled = !isArchiving,
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
                            enabled = openEnabled,
                        ) {
                            Text(setupActionLabel(defaultLabel = "Open", setupStatus = setupStatus))
                        }

                        WorktreeMenuAction.CreateWorktree -> DropdownMenuItem(
                            onClick = {
                                menuExpanded = false
                                onOpenCreateWorktreeDialog()
                            },
                            enabled = createEnabled,
                        ) {
                            Text("Create worktree")
                        }

                        WorktreeMenuAction.Archive -> DropdownMenuItem(
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            },
                            enabled = archiveEnabled,
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
private fun CreateWorktreeDialog(
    state: PendingCreateWorktree,
    onTargetBranchChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val branchNameValidator = remember { WorktreeBranchNameValidator() }
    var targetBranchValidation by remember(state.baseBranch, state.targetBranch) {
        mutableStateOf(
            startCreateWorktreeTargetBranchValidation(
                baseBranch = state.baseBranch,
                targetBranch = state.targetBranch,
                branchNameValidator = branchNameValidator,
            ),
        )
    }
    LaunchedEffect(state.baseBranch, state.targetBranch) {
        if (!targetBranchValidation.isCheckingGitRefFormat) return@LaunchedEffect

        val targetBranch = state.targetBranch
        val baseBranch = state.baseBranch
        targetBranchValidation = withContext(Dispatchers.IO) {
            finishCreateWorktreeTargetBranchValidation(
                baseBranch = baseBranch,
                targetBranch = targetBranch,
                branchNameValidator = branchNameValidator,
            )
        }
    }
    val validationMessage = createWorktreeTargetBranchValidationMessage(
        targetBranch = state.targetBranch,
        validation = targetBranchValidation,
    )
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Create Worktree",
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
                    Text(text = "Create Worktree", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Base: ${state.baseBranch}")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.targetBranch,
                        onValueChange = onTargetBranchChange,
                        label = { Text("Target branch") },
                        isError = validationMessage != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    validationMessage?.let { message ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = {}, enabled = isCreateWorktreeConfirmEnabled(targetBranchValidation)) {
                            Text("Create")
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

@Composable
private fun ForceArchiveWorktreeDialog(
    worktreePath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Force Archive Worktree",
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
                    Text(text = "Force Archive Worktree", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "This worktree has local changes. Force removal will discard them.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = worktreePath, style = MaterialTheme.typography.caption)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = onConfirm) {
                            Text("Force Archive")
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

private fun LocalWorktreeUiState.isDetachedDisplayBranch(): Boolean = branch == "(detached)"

private fun String.normalizedWorktreePath(): String = trim().trimEnd('/', '\\')
