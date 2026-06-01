package com.github.karlsabo.devlake.enghub.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.github.karlsabo.git.WorktreeBranchNameValidationResult
import com.github.karlsabo.git.WorktreeBranchNameValidator

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
        result = validateGitRefFormatWhenNeeded(
            localValidation = localValidation,
            targetBranchMatchesBase = targetBranchMatchesBase,
            targetBranch = targetBranch,
            branchNameValidator = branchNameValidator,
        ),
        isCheckingGitRefFormat = false,
        targetBranchMatchesBase = targetBranchMatchesBase,
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
    validation.targetBranchMatchesBase -> TARGET_BRANCH_MATCHES_BASE_MESSAGE
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

private fun targetBranchMatchesBase(
    baseBranch: String,
    targetBranch: String,
): Boolean = targetBranch.isNotEmpty() && targetBranch == baseBranch
