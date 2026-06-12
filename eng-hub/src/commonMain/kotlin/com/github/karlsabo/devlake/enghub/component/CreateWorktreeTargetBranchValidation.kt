package com.github.karlsabo.devlake.enghub.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.github.karlsabo.git.WorktreeBranchNameValidationResult
import com.github.karlsabo.git.WorktreeBranchNameValidator

internal data class CreateWorktreeTargetBranchValidation(
    val result: WorktreeBranchNameValidationResult,
    val isCheckingGitRefFormat: Boolean,
    val targetBranchMatchesBase: Boolean = false,
    val targetBranchMatchesBaseMessage: String = TARGET_BRANCH_MATCHES_BASE_BRANCH_MESSAGE,
)

private const val TARGET_BRANCH_MATCHES_BASE_BRANCH_MESSAGE = "Target branch must differ from the base branch"
private const val TARGET_BRANCH_MATCHES_BASE_COMMIT_ISH_MESSAGE = "Target branch must differ from the base commit-ish"

internal fun startCreateWorktreeTargetBranchValidation(
    baseBranch: String,
    targetBranch: String,
    branchNameValidator: WorktreeBranchNameValidator,
    baseCommitIsh: String? = null,
): CreateWorktreeTargetBranchValidation {
    val localValidation = branchNameValidator.validateWithoutGitRefFormatCheck(targetBranch)
    val baseComparison = createBaseComparison(baseBranch, baseCommitIsh)
    val targetBranchMatchesBase = targetBranchMatchesBase(baseComparison.ref, targetBranch)
    return CreateWorktreeTargetBranchValidation(
        result = localValidation,
        isCheckingGitRefFormat = localValidation.isValid && !targetBranchMatchesBase,
        targetBranchMatchesBase = targetBranchMatchesBase,
        targetBranchMatchesBaseMessage = baseComparison.matchMessage,
    )
}

internal fun finishCreateWorktreeTargetBranchValidation(
    baseBranch: String,
    targetBranch: String,
    branchNameValidator: WorktreeBranchNameValidator,
    baseCommitIsh: String? = null,
): CreateWorktreeTargetBranchValidation {
    val localValidation = branchNameValidator.validateWithoutGitRefFormatCheck(targetBranch)
    val baseComparison = createBaseComparison(baseBranch, baseCommitIsh)
    val targetBranchMatchesBase = targetBranchMatchesBase(baseComparison.ref, targetBranch)
    return CreateWorktreeTargetBranchValidation(
        result = validateGitRefFormatWhenNeeded(
            localValidation = localValidation,
            targetBranchMatchesBase = targetBranchMatchesBase,
            targetBranch = targetBranch,
            branchNameValidator = branchNameValidator,
        ),
        isCheckingGitRefFormat = false,
        targetBranchMatchesBase = targetBranchMatchesBase,
        targetBranchMatchesBaseMessage = baseComparison.matchMessage,
    )
}

private fun validateGitRefFormatWhenNeeded(
    localValidation: WorktreeBranchNameValidationResult,
    targetBranchMatchesBase: Boolean,
    targetBranch: String,
    branchNameValidator: WorktreeBranchNameValidator,
): WorktreeBranchNameValidationResult = if (localValidation.isValid && !targetBranchMatchesBase) {
    branchNameValidator.validate(targetBranch)
} else {
    localValidation
}

internal fun createWorktreeTargetBranchValidationMessage(
    targetBranch: String,
    validation: CreateWorktreeTargetBranchValidation,
): String? = when {
    targetBranch.isEmpty() -> null
    validation.isCheckingGitRefFormat -> null
    validation.targetBranchMatchesBase -> validation.targetBranchMatchesBaseMessage
    else -> validation.result.message
}

internal fun isCreateWorktreeConfirmEnabled(
    validation: CreateWorktreeTargetBranchValidation,
): Boolean = !validation.isCheckingGitRefFormat &&
    validation.result.isValid &&
    !validation.targetBranchMatchesBase

internal fun createTargetBranchInputValue(targetBranch: String): TextFieldValue = TextFieldValue(
    text = targetBranch,
    selection = TextRange(targetBranch.length),
)

private data class BaseComparison(
    val ref: String,
    val matchMessage: String,
)

private fun createBaseComparison(
    baseBranch: String,
    baseCommitIsh: String?,
): BaseComparison = if (baseCommitIsh == null) {
    BaseComparison(baseBranch, TARGET_BRANCH_MATCHES_BASE_BRANCH_MESSAGE)
} else {
    BaseComparison(baseCommitIsh, TARGET_BRANCH_MATCHES_BASE_COMMIT_ISH_MESSAGE)
}

private fun targetBranchMatchesBase(
    baseRef: String,
    targetBranch: String,
): Boolean = targetBranch.isNotEmpty() && targetBranch == baseRef
