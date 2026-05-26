package com.github.karlsabo.git

enum class WorktreeBranchNameValidationFailure(val message: String) {
    EMPTY("Branch name is required"),
    WHITESPACE("Branch name must not contain whitespace"),
    CONTROL_CHARACTER("Branch name must not contain control characters"),
    INVALID_GIT_REF_FORMAT("Branch name is not a valid git branch name"),
}

sealed interface WorktreeBranchNameValidationResult {
    val failure: WorktreeBranchNameValidationFailure?
    val isValid: Boolean get() = failure == null
    val message: String? get() = failure?.message

    data object Valid : WorktreeBranchNameValidationResult {
        override val failure: WorktreeBranchNameValidationFailure? = null
    }

    data class Invalid(override val failure: WorktreeBranchNameValidationFailure) : WorktreeBranchNameValidationResult
}

class WorktreeBranchNameValidator(
    private val isValidGitBranchRefFormat: (String) -> Boolean = { branch ->
        GitCommandService().isValidBranchRefFormat(branch)
    },
) {
    fun validate(branch: String): WorktreeBranchNameValidationResult = when {
        branch.isEmpty() -> WorktreeBranchNameValidationResult.Invalid(WorktreeBranchNameValidationFailure.EMPTY)
        branch.any { it.isWhitespace() } -> {
            WorktreeBranchNameValidationResult.Invalid(WorktreeBranchNameValidationFailure.WHITESPACE)
        }

        branch.any { it.isISOControl() } -> {
            WorktreeBranchNameValidationResult.Invalid(WorktreeBranchNameValidationFailure.CONTROL_CHARACTER)
        }

        !isValidGitBranchRefFormat(branch) -> {
            WorktreeBranchNameValidationResult.Invalid(WorktreeBranchNameValidationFailure.INVALID_GIT_REF_FORMAT)
        }

        else -> WorktreeBranchNameValidationResult.Valid
    }
}

fun GitCommandApi.isValidBranchRefFormat(branch: String): Boolean = try {
    execute(null, "check-ref-format", "--branch", branch)
    true
} catch (_: GitCommandException) {
    false
}

