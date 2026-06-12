package com.github.karlsabo.devlake.enghub.component

import androidx.compose.ui.text.TextRange
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeBranchNameValidator
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreePanelTest {

    @Test
    fun visibleWorktreeRowsNestOneChildLevelUnderParent() {
        val base = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )
        val stacked = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        )

        val rows = visibleWorktreeRows(listOf(stacked, base))

        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), rows.map { it.worktree.branch })
        assertEquals(listOf(0, 1), rows.map { it.nestingDepth })
    }

    @Test
    fun visibleWorktreeRowsFallBackToFlatListWhenParentIsMissing() {
        val stacked = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        )
        val main = LocalWorktreeUiState(
            branch = "main",
            path = "/repos/dev-lake-utils",
            isRoot = true,
        )

        val rows = visibleWorktreeRows(listOf(stacked, main))

        assertEquals(listOf("feature/stacked-pr", "main"), rows.map { it.worktree.branch })
        assertEquals(listOf(0, 0), rows.map { it.nestingDepth })
    }

    @Test
    fun repositoryMenuExposesCreateWorktreeForConfiguredRepository() {
        val repository = LocalRepositoryUiState(
            name = "dev-lake-utils",
            path = "/repos/dev-lake-utils",
        )

        assertEquals(
            listOf(RepositoryMenuAction.CreateWorktree),
            visibleRepositoryMenuActions(repository),
        )
    }

    @Test
    fun repositoryCreateWorktreeActionIsEnabledWhenRepositoryBaseIsIdle() {
        val repository = LocalRepositoryUiState(
            name = "dev-lake-utils",
            path = "/repos/dev-lake-utils",
        )

        assertTrue(
            isRepositoryCreateWorktreeEnabled(
                repository = repository,
                setupStatus = null,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun repositoryCreateWorktreeActionIsDisabledWhileBaseSetupIsInProgress() {
        val repository = LocalRepositoryUiState(
            name = "dev-lake-utils",
            path = "/repos/dev-lake-utils",
        )

        assertFalse(
            isRepositoryCreateWorktreeEnabled(
                repository = repository,
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun repositoryCreateWorktreeActionIsDisabledWhileBaseArchiveIsInProgress() {
        val repository = LocalRepositoryUiState(
            name = "dev-lake-utils",
            path = "/repos/dev-lake-utils",
        )

        assertFalse(
            isRepositoryCreateWorktreeEnabled(
                repository = repository,
                setupStatus = null,
                isArchiving = true,
            ),
        )
    }

    @Test
    fun rootWorktreeMenuExposesCreateWorktreeButNotArchive() {
        val worktree = LocalWorktreeUiState(
            branch = "main",
            path = "/repos/dev-lake-utils",
            isRoot = true,
        )

        assertEquals(
            listOf(WorktreeMenuAction.Open, WorktreeMenuAction.CreateWorktree),
            visibleWorktreeMenuActions(worktree),
        )
    }

    @Test
    fun nonRootWorktreeMenuExposesCreateWorktreeAndArchive() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/worktree-panel",
            path = "/repos/dev-lake-utils-feature-worktree-panel",
            isRoot = false,
        )

        assertEquals(
            listOf(
                WorktreeMenuAction.Open,
                WorktreeMenuAction.CreateWorktree,
                WorktreeMenuAction.Archive,
            ),
            visibleWorktreeMenuActions(worktree),
        )
    }

    @Test
    fun createWorktreeDialogUsesSelectedBaseAndEmptyTargetBranch() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
            isRoot = false,
        )

        assertEquals(
            PendingCreateWorktree(
                repoRootPath = "/repos/dev-lake-utils",
                baseWorktreePath = "/repos/dev-lake-utils-feature-base-pr",
                baseBranch = "feature/base-pr",
                targetBranch = "",
            ),
            createWorktreeDialogState(
                repoRootPath = "/repos/dev-lake-utils",
                worktree = worktree,
            ),
        )
    }

    @Test
    fun repositoryCreateWorktreeDialogUsesDefaultBranchBaseAndEmptyTargetBranch() {
        assertEquals(
            PendingCreateWorktree(
                repoRootPath = "/repos/dev-lake-utils",
                baseWorktreePath = "/repos/dev-lake-utils",
                baseBranch = "main",
                targetBranch = "",
            ),
            createRepositoryWorktreeDialogState(
                repoRootPath = "/repos/dev-lake-utils",
                baseWorktreePath = "/repos/dev-lake-utils",
                baseBranch = "main",
            ),
        )
    }

    @Test
    fun createWorktreeDialogInitializesTargetBranchInputCaretAtEnd() {
        val targetBranch = "feature/stacked-pr"
        val input = createTargetBranchInputValue(targetBranch)

        assertEquals(targetBranch, input.text)
        assertEquals(TextRange(targetBranch.length), input.selection)
    }

    @Test
    fun submittingCreateWorktreeDialogCallsBoundaryWithSelectedBaseAndTargetBranch() {
        val submissions = mutableListOf<PendingCreateWorktree>()
        val state = PendingCreateWorktree(
            repoRootPath = "/repos/dev-lake-utils",
            baseWorktreePath = "/repos/dev-lake-utils-feature-base-pr",
            baseBranch = "feature/base-pr",
            targetBranch = "feature/stacked-pr",
        )

        submitCreateWorktreeDialog(state) { repoRootPath, baseWorktreePath, baseBranch, targetBranch ->
            submissions += PendingCreateWorktree(
                repoRootPath = repoRootPath,
                baseWorktreePath = baseWorktreePath,
                baseBranch = baseBranch,
                targetBranch = targetBranch,
            )
        }

        assertEquals(listOf(state), submissions)
    }

    @Test
    fun confirmingUseUnrelatedExistingBranchDialogCallsBoundaryWithOriginalRequest() {
        val confirmations = mutableListOf<PendingUseUnrelatedExistingBranch>()
        val request = PendingUseUnrelatedExistingBranch(
            repoRootPath = "/repos/dev-lake-utils",
            baseWorktreePath = "/repos/dev-lake-utils-feature-base-pr",
            baseBranch = "feature/base-pr",
            targetBranch = "feature/stacked-pr",
        )

        confirmUseUnrelatedExistingBranchDialog(request) { confirmations += it }

        assertEquals(listOf(request), confirmations)
    }

    @Test
    fun dismissingUseUnrelatedExistingBranchDialogCallsDismissBoundary() {
        var dismissCount = 0

        dismissUseUnrelatedExistingBranchDialog { dismissCount += 1 }

        assertEquals(1, dismissCount)
    }

    @Test
    fun createWorktreeActionIsDisabledWhileSetupIsInProgress() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )

        assertFalse(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsDisabledWhileArchiveIsInProgress() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )

        assertFalse(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = null,
                isArchiving = true,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsDisabledForDetachedWorktree() {
        val worktree = LocalWorktreeUiState(
            branch = "(detached)",
            path = "/repos/dev-lake-utils-detached",
        )

        assertFalse(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = null,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsEnabledForIdleBranchWorktree() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )

        assertTrue(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = null,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun createWorktreeDialogShowsInlineValidationAndDisablesCreateForWhitespaceTargetBranch() {
        val validation = startCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature/new dashboard",
            branchNameValidator = WorktreeBranchNameValidator { true },
        )

        assertEquals(
            "Branch name must not contain whitespace",
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature/new dashboard",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogDisablesCreateAndShowsInlineValidationForTargetBranchMatchingBase() {
        val validation = startCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature/base-pr",
            branchNameValidator = WorktreeBranchNameValidator {
                error("git check should not run when target branch matches the base")
            },
        )

        assertEquals(
            "Target branch must differ from the base branch",
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature/base-pr",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogStillRejectsMatchingBaseWhenAsyncValidationCompletes() {
        val validation = finishCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature/base-pr",
            branchNameValidator = WorktreeBranchNameValidator {
                error("git check should not run when target branch matches the base")
            },
        )

        assertEquals(
            "Target branch must differ from the base branch",
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature/base-pr",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogDisablesCreateWithoutShowingInlineValidationForEmptyTargetBranch() {
        val validation = startCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "",
            branchNameValidator = WorktreeBranchNameValidator { true },
        )

        assertEquals(
            null,
            createWorktreeTargetBranchValidationMessage(targetBranch = "", validation = validation),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogKeepsCreateDisabledWhileGitRefFormatCheckIsPending() {
        val validation = startCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature/new-dashboard",
            branchNameValidator = WorktreeBranchNameValidator {
                error("git check should run from the async validation boundary")
            },
        )

        assertEquals(
            null,
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature/new-dashboard",
                validation = validation,
            ),
        )
        assertTrue(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogEnablesCreateAfterValidGitRefFormatCheckCompletes() {
        val validation = finishCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature/new-dashboard",
            branchNameValidator = WorktreeBranchNameValidator { true },
        )

        assertEquals(
            null,
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature/new-dashboard",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertTrue(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogDisablesCreateAfterInvalidGitRefFormatCheckCompletes() {
        val validation = finishCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature//new-dashboard",
            branchNameValidator = WorktreeBranchNameValidator { false },
        )

        assertEquals(
            "Branch name is not a valid git branch name",
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature//new-dashboard",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun archiveActionIsDisabledWhileSetupIsInProgress() {
        assertFalse(
            isWorktreeArchiveEnabled(
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun archiveActionIsDisabledWhileArchiveIsInProgress() {
        assertFalse(isWorktreeArchiveEnabled(setupStatus = null, isArchiving = true))
    }

    @Test
    fun archiveActionIsEnabledWhenWorktreeIsIdle() {
        assertTrue(isWorktreeArchiveEnabled(setupStatus = null, isArchiving = false))
    }
}
