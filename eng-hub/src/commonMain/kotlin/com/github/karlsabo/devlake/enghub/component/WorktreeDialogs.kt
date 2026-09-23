package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.git.WorktreeBranchNameValidator
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

internal data class WorktreeDialogState(
    val pendingCreateWorktree: PendingCreateWorktree?,
    val existingBranchDiscovery: ExistingBranchDiscoveryUiState = ExistingBranchDiscoveryUiState(),
    val useUnrelatedExistingBranchConfirmationRequest: PendingUseUnrelatedExistingBranch?,
    val worktreeConflictResolutionRequest: PendingWorktreeConflictResolution?,
    val forceArchiveRequest: ForceArchiveWorktreeUiState?,
)

internal data class WorktreeDialogActions(
    val onPendingCreateWorktreeChange: (PendingCreateWorktree?) -> Unit,
    val onCreateWorktree: (PendingCreateWorktree) -> Unit,
    val onCheckoutExistingBranch: (repoRootPath: String, branch: String, existingWorktreePath: String?) -> Unit,
    val onConfirmUseUnrelatedExistingBranch: (PendingUseUnrelatedExistingBranch) -> Unit,
    val onDismissUseUnrelatedExistingBranchConfirmation: () -> Unit,
    val onAbortWorktreeConflict: (PendingWorktreeConflictResolution) -> Unit,
    val onLeaveWorktreeConflictAsIs: (PendingWorktreeConflictResolution) -> Unit,
    val forceArchive: ForceArchiveWorktreeActions,
)

private data class CreateWorktreeDialogModel(
    val validation: CreateWorktreeTargetBranchValidation,
    val onInputChange: (String) -> Unit,
)

private data class CreateWorktreeDialogContentState(
    val request: PendingCreateWorktree,
    val validationMessage: String?,
    val confirmEnabled: Boolean,
)

private data class CreateWorktreeDialogActions(
    val onStateChange: (PendingCreateWorktree) -> Unit,
    val onCreateNew: (PendingCreateWorktree) -> Unit,
    val onCheckoutExisting: (ExistingWorktreeResult) -> Unit,
    val onDismiss: () -> Unit,
)

private data class CreateWorktreeDialogContentActions(
    val onTargetBranchInputChange: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun WorktreeDialogHost(
    state: WorktreeDialogState,
    actions: WorktreeDialogActions,
) {
    state.forceArchiveRequest?.let { archive ->
        ForceArchiveWorktreeDialog(
            worktreePath = archive.worktreePath,
            onConfirm = { actions.forceArchive.onConfirm(archive.repoRootPath, archive.worktreePath) },
            onDismiss = actions.forceArchive.onDismiss,
        )
    }

    state.useUnrelatedExistingBranchConfirmationRequest?.let { request ->
        UseUnrelatedExistingBranchConfirmationDialog(
            request = request,
            onConfirm = {
                confirmUseUnrelatedExistingBranchDialog(request, actions.onConfirmUseUnrelatedExistingBranch)
            },
            onDismiss = {
                dismissUseUnrelatedExistingBranchDialog(actions.onDismissUseUnrelatedExistingBranchConfirmation)
            },
        )
    }

    state.worktreeConflictResolutionRequest?.let { request ->
        WorktreeConflictResolutionDialog(
            request = request,
            onAbort = { abortWorktreeConflictDialog(request, actions.onAbortWorktreeConflict) },
            onLeaveAsIs = { leaveWorktreeConflictAsIsDialog(request, actions.onLeaveWorktreeConflictAsIs) },
        )
    }

    state.pendingCreateWorktree?.let { createWorktree ->
        CreateWorktreeDialog(
            state = createWorktree,
            discovery = state.existingBranchDiscovery,
            actions = CreateWorktreeDialogActions(
                onStateChange = actions.onPendingCreateWorktreeChange,
                onCreateNew = { request ->
                    actions.onPendingCreateWorktreeChange(null)
                    actions.onCreateWorktree(request)
                },
                onCheckoutExisting = { result ->
                    actions.onPendingCreateWorktreeChange(null)
                    actions.onCheckoutExistingBranch(result.repoRootPath, result.branch, result.existingWorktreePath)
                },
                onDismiss = { actions.onPendingCreateWorktreeChange(null) },
            ),
        )
    }
}

@Composable
private fun WorktreeConflictResolutionDialog(
    request: PendingWorktreeConflictResolution,
    onAbort: () -> Unit,
    onLeaveAsIs: () -> Unit,
) {
    val content = worktreeConflictDialogContent(request)
    DialogWindow(
        onCloseRequest = onLeaveAsIs,
        title = content.windowTitle,
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
                    Text(text = content.heading, style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(content.summary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(content.guidance)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = onAbort) {
                            Text("Abort")
                        }
                        if (content.canLeaveAsIs) {
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
}

@Composable
private fun UseUnrelatedExistingBranchConfirmationDialog(
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
private fun CreateWorktreeDialog(
    state: PendingCreateWorktree,
    discovery: ExistingBranchDiscoveryUiState,
    actions: CreateWorktreeDialogActions,
) {
    val model = rememberCreateWorktreeDialogModel(
        state = state,
        onTargetBranchChange = { targetBranch -> actions.onStateChange(state.copy(targetBranch = targetBranch)) },
    )
    val validationMessage = createWorktreeTargetBranchValidationMessage(
        targetBranch = state.targetBranch,
        validation = model.validation,
    )

    DialogWindow(
        onCloseRequest = actions.onDismiss,
        title = "Create Worktree",
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface {
                if (state.mode == CreateWorktreeMode.NEW) {
                    CreateWorktreeDialogContent(
                        state = CreateWorktreeDialogContentState(
                            request = state,
                            validationMessage = validationMessage,
                            confirmEnabled = isCreateWorktreeConfirmEnabled(model.validation),
                        ),
                        actions = CreateWorktreeDialogContentActions(
                            onTargetBranchInputChange = model.onInputChange,
                            onConfirm = { actions.onCreateNew(state) },
                            onDismiss = actions.onDismiss,
                        ),
                        onModeChange = { mode -> actions.onStateChange(state.copy(mode = mode)) },
                    )
                } else {
                    ExistingBranchWorktreeDialogContent(
                        request = state,
                        discovery = discovery,
                        onRequestChange = actions.onStateChange,
                        onConfirm = actions.onCheckoutExisting,
                        onDismiss = actions.onDismiss,
                    )
                }
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
    var targetBranchValidation by remember(state.baseBranch, state.baseCommitIsh, state.targetBranch) {
        mutableStateOf(startValidation(state, branchNameValidator))
    }

    LaunchedEffect(state.baseBranch, state.baseCommitIsh, state.targetBranch) {
        if (targetBranchValidation.isCheckingGitRefFormat) {
            targetBranchValidation = finishValidationOnIo(state, branchNameValidator)
        }
    }

    return CreateWorktreeDialogModel(
        validation = targetBranchValidation,
        onInputChange = onTargetBranchChange,
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
private fun CreateWorktreeDialogContent(
    state: CreateWorktreeDialogContentState,
    actions: CreateWorktreeDialogContentActions,
    onModeChange: (CreateWorktreeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        Text(text = "Create Worktree", style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        CreateWorktreeModeSelector(state.request.mode, onModeChange)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Base: ${state.request.baseCommitIsh ?: state.request.baseBranch}")
        Spacer(modifier = Modifier.height(12.dp))
        CreateWorktreeTargetBranchField(
            targetBranch = state.request.targetBranch,
            validationMessage = state.validationMessage,
            onTargetBranchInputChange = actions.onTargetBranchInputChange,
        )
        Spacer(modifier = Modifier.height(16.dp))
        CreateWorktreeDialogButtons(state, actions)
    }
}

@Composable
internal fun CreateWorktreeTargetBranchField(
    targetBranch: String,
    validationMessage: String?,
    onTargetBranchInputChange: (String) -> Unit,
) {
    val targetBranchInput = rememberTextFieldState(initialText = targetBranch)
    val currentOnTargetBranchInputChange by rememberUpdatedState(onTargetBranchInputChange)

    LaunchedEffect(targetBranchInput) {
        snapshotFlow { targetBranchInput.text.toString() }
            .distinctUntilChanged()
            .collect { currentOnTargetBranchInputChange(it) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            state = targetBranchInput,
            label = { Text("Target branch") },
            isError = validationMessage != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-worktree-target-branch"),
        )
        validationMessage?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun CreateWorktreeDialogButtons(
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
