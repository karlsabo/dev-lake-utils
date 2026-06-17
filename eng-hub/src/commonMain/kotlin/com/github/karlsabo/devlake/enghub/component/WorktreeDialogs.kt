package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.git.WorktreeBranchNameValidator
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

internal data class WorktreeDialogState(
    val pendingArchive: PendingArchive?,
    val pendingCreateWorktree: PendingCreateWorktree?,
    val useUnrelatedExistingBranchConfirmationRequest: PendingUseUnrelatedExistingBranch?,
    val rebaseConflictResolutionRequest: PendingRebaseConflictResolution?,
    val forceArchiveRequest: ForceArchiveWorktreeUiState?,
)

internal data class WorktreeDialogActions(
    val onPendingArchiveChange: (PendingArchive?) -> Unit,
    val onPendingCreateWorktreeChange: (PendingCreateWorktree?) -> Unit,
    val onArchiveWorktree: (PendingArchive) -> Unit,
    val onCreateWorktree: (PendingCreateWorktree) -> Unit,
    val onConfirmUseUnrelatedExistingBranch: (PendingUseUnrelatedExistingBranch) -> Unit,
    val onDismissUseUnrelatedExistingBranchConfirmation: () -> Unit,
    val onAbortRebaseConflict: (PendingRebaseConflictResolution) -> Unit,
    val onLeaveRebaseConflictAsIs: (PendingRebaseConflictResolution) -> Unit,
    val forceArchive: ForceArchiveWorktreeActions,
)

private data class CreateWorktreeDialogModel(
    val targetBranchInput: TextFieldValue,
    val validation: CreateWorktreeTargetBranchValidation,
    val onInputChange: (TextFieldValue) -> Unit,
)

private data class CreateWorktreeDialogContentState(
    val request: PendingCreateWorktree,
    val targetBranchInput: TextFieldValue,
    val validationMessage: String?,
    val confirmEnabled: Boolean,
)

private data class CreateWorktreeDialogContentActions(
    val onTargetBranchInputChange: (TextFieldValue) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun worktreeDialogHost(
    state: WorktreeDialogState,
    actions: WorktreeDialogActions,
) {
    state.pendingArchive?.let { archive ->
        archiveWorktreeDialog(
            worktreePath = archive.worktreePath,
            onConfirm = {
                actions.onPendingArchiveChange(null)
                actions.onArchiveWorktree(archive)
            },
            onDismiss = { actions.onPendingArchiveChange(null) },
        )
    }

    state.forceArchiveRequest?.let { archive ->
        forceArchiveWorktreeDialog(
            worktreePath = archive.worktreePath,
            onConfirm = { actions.forceArchive.onConfirm(archive.repoRootPath, archive.worktreePath) },
            onDismiss = actions.forceArchive.onDismiss,
        )
    }

    state.useUnrelatedExistingBranchConfirmationRequest?.let { request ->
        useUnrelatedExistingBranchConfirmationDialog(
            request = request,
            onConfirm = {
                confirmUseUnrelatedExistingBranchDialog(request, actions.onConfirmUseUnrelatedExistingBranch)
            },
            onDismiss = {
                dismissUseUnrelatedExistingBranchDialog(actions.onDismissUseUnrelatedExistingBranchConfirmation)
            },
        )
    }

    state.rebaseConflictResolutionRequest?.let { request ->
        rebaseConflictResolutionDialog(
            request = request,
            onAbort = { abortRebaseConflictDialog(request, actions.onAbortRebaseConflict) },
            onLeaveAsIs = { leaveRebaseConflictAsIsDialog(request, actions.onLeaveRebaseConflictAsIs) },
        )
    }

    state.pendingCreateWorktree?.let { createWorktree ->
        createWorktreeDialog(
            state = createWorktree,
            onTargetBranchChange = { targetBranch ->
                actions.onPendingCreateWorktreeChange(createWorktree.copy(targetBranch = targetBranch))
            },
            onConfirm = { request ->
                actions.onPendingCreateWorktreeChange(null)
                actions.onCreateWorktree(request)
            },
            onDismiss = { actions.onPendingCreateWorktreeChange(null) },
        )
    }
}

@Composable
private fun rebaseConflictResolutionDialog(
    request: PendingRebaseConflictResolution,
    onAbort: () -> Unit,
    onLeaveAsIs: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onLeaveAsIs,
        title = "Rebase Conflict",
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
                    Text(text = "Rebase conflict", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Rebase onto ${request.parentBranch} stopped with conflicts in ${request.worktreePath}.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Abort the rebase or leave the worktree as-is for manual conflict resolution.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = onAbort) {
                            Text("Abort")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onLeaveAsIs) {
                            Text("Leave as-is")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun useUnrelatedExistingBranchConfirmationDialog(
    request: PendingUseUnrelatedExistingBranch,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Use Existing Branch?",
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
                    Text(text = "Use existing branch?", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Existing branch ${request.targetBranch} is not descended from " +
                            "selected base ${request.baseBranch}.",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Continue to create or reuse a worktree for ${request.targetBranch}?")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = onConfirm) {
                            Text("Continue")
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
private fun createWorktreeDialog(
    state: PendingCreateWorktree,
    onTargetBranchChange: (String) -> Unit,
    onConfirm: (PendingCreateWorktree) -> Unit,
    onDismiss: () -> Unit,
) {
    val model = rememberCreateWorktreeDialogModel(
        state = state,
        onTargetBranchChange = onTargetBranchChange,
    )
    val validationMessage = createWorktreeTargetBranchValidationMessage(
        targetBranch = state.targetBranch,
        validation = model.validation,
    )

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Create Worktree",
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface {
                createWorktreeDialogContent(
                    state = CreateWorktreeDialogContentState(
                        request = state,
                        targetBranchInput = model.targetBranchInput,
                        validationMessage = validationMessage,
                        confirmEnabled = isCreateWorktreeConfirmEnabled(model.validation),
                    ),
                    actions = CreateWorktreeDialogContentActions(
                        onTargetBranchInputChange = model.onInputChange,
                        onConfirm = { onConfirm(state) },
                        onDismiss = onDismiss,
                    ),
                )
            }
        }
    }
}

@Composable
private fun rememberCreateWorktreeDialogModel(
    state: PendingCreateWorktree,
    onTargetBranchChange: (String) -> Unit,
): CreateWorktreeDialogModel {
    val branchNameValidator = remember { WorktreeBranchNameValidator() }
    var targetBranchInput by remember(
        state.repoRootPath,
        state.baseWorktreePath,
        state.baseBranch,
        state.baseCommitIsh,
    ) {
        mutableStateOf(createTargetBranchInputValue(state.targetBranch))
    }
    var targetBranchValidation by remember(state.baseBranch, state.baseCommitIsh, state.targetBranch) {
        mutableStateOf(startValidation(state, branchNameValidator))
    }

    LaunchedEffect(state.baseBranch, state.baseCommitIsh, state.targetBranch) {
        if (targetBranchValidation.isCheckingGitRefFormat) {
            targetBranchValidation = finishValidationOnIo(state, branchNameValidator)
        }
    }

    return CreateWorktreeDialogModel(
        targetBranchInput = targetBranchInput,
        validation = targetBranchValidation,
        onInputChange = { input ->
            targetBranchInput = input
            onTargetBranchChange(input.text)
        },
    )
}

private fun startValidation(
    state: PendingCreateWorktree,
    branchNameValidator: WorktreeBranchNameValidator,
): CreateWorktreeTargetBranchValidation = startCreateWorktreeTargetBranchValidation(
    baseBranch = state.baseBranch,
    targetBranch = state.targetBranch,
    branchNameValidator = branchNameValidator,
    baseCommitIsh = state.baseCommitIsh,
)

private suspend fun finishValidationOnIo(
    state: PendingCreateWorktree,
    branchNameValidator: WorktreeBranchNameValidator,
): CreateWorktreeTargetBranchValidation = withContext(Dispatchers.IO) {
    finishCreateWorktreeTargetBranchValidation(
        baseBranch = state.baseBranch,
        targetBranch = state.targetBranch,
        branchNameValidator = branchNameValidator,
        baseCommitIsh = state.baseCommitIsh,
    )
}

@Composable
private fun createWorktreeDialogContent(
    state: CreateWorktreeDialogContentState,
    actions: CreateWorktreeDialogContentActions,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        Text(text = "Create Worktree", style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Base: ${state.request.baseCommitIsh ?: state.request.baseBranch}")
        Spacer(modifier = Modifier.height(12.dp))
        createWorktreeTargetBranchField(state, actions.onTargetBranchInputChange)
        Spacer(modifier = Modifier.height(16.dp))
        createWorktreeDialogButtons(state, actions)
    }
}

@Composable
private fun createWorktreeTargetBranchField(
    state: CreateWorktreeDialogContentState,
    onTargetBranchInputChange: (TextFieldValue) -> Unit,
) {
    OutlinedTextField(
        value = state.targetBranchInput,
        onValueChange = onTargetBranchInputChange,
        label = { Text("Target branch") },
        isError = state.validationMessage != null,
        modifier = Modifier.fillMaxWidth(),
    )
    state.validationMessage?.let { message ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            color = MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Composable
private fun createWorktreeDialogButtons(
    state: CreateWorktreeDialogContentState,
    actions: CreateWorktreeDialogContentActions,
) {
    Row {
        Button(
            onClick = actions.onConfirm,
            enabled = state.confirmEnabled,
        ) {
            Text("Create")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = actions.onDismiss) {
            Text("Cancel")
        }
    }
}
