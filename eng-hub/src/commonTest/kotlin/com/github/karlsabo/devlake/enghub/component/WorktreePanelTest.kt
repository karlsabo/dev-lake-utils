package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rebaseNeededWorktreeRowRendersIndicatorLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = rebaseNeededRow(),
                    onOpen = {},
                    onArchive = {},
                    onOpenCreateWorktreeDialog = {},
                )
            }
        }

        onNodeWithText("Rebase needed").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rebasingWorktreeRowRendersProgressLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = upToDateRow().copy(isRebasing = true),
                    onOpen = {},
                    onArchive = {},
                    onOpenCreateWorktreeDialog = {},
                )
            }
        }

        onNodeWithText("Rebasing...").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun upToDateWorktreeRowDoesNotRenderRebaseNeededIndicatorLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = upToDateRow(),
                    onOpen = {},
                    onArchive = {},
                    onOpenCreateWorktreeDialog = {},
                )
            }
        }

        onAllNodesWithText("Rebase needed").assertCountEquals(0)
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun choosingRebaseOntoParentCallsRowBoundary() = runComposeUiTest {
        val rebaseRequests = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/stacked-pr",
                            path = "/repos/dev-lake-utils-feature-stacked-pr",
                            parentBranch = "feature/base-pr",
                        ),
                        setupStatus = null,
                        isArchiving = false,
                    ),
                    onOpen = {},
                    onArchive = {},
                    onOpenCreateWorktreeDialog = {},
                    onRebaseOntoParent = { rebaseRequests += Unit },
                )
            }
        }

        onNodeWithContentDescription("Worktree actions for feature/stacked-pr").performClick()
        onNodeWithText("Rebase onto parent").performClick()

        assertEquals(1, rebaseRequests.size)
    }

    @Test
    fun worktreeMenuExposesRebaseOntoParentForWorktreeWithInferredParent() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        )

        assertEquals(
            listOf(
                WorktreeMenuAction.Open,
                WorktreeMenuAction.CreateWorktree,
                WorktreeMenuAction.RebaseOntoParent,
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
    fun createWorktreeDialogCarriesDetachedBaseCommitHash() {
        val worktree = LocalWorktreeUiState(
            branch = "(detached)",
            path = "/repos/dev-lake-utils-detached",
            baseCommitHash = "abc123",
        )

        assertEquals(
            PendingCreateWorktree(
                repoRootPath = "/repos/dev-lake-utils",
                baseWorktreePath = "/repos/dev-lake-utils-detached",
                baseBranch = "(detached)",
                baseCommitIsh = "abc123",
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

        submitCreateWorktreeDialog(state) { request ->
            submissions += request
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
    fun createWorktreeActionIsEnabledForDetachedWorktreeWithBaseCommitHash() {
        val worktree = LocalWorktreeUiState(
            branch = "(detached)",
            path = "/repos/dev-lake-utils-detached",
            baseCommitHash = "abc123",
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
    fun createWorktreeActionIsDisabledForDetachedWorktreeWithoutBaseCommitHash() {
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
    fun createWorktreeDialogDisablesCreateForTargetBranchMatchingDetachedBaseCommitIsh() {
        val validation = startCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "abc123",
            branchNameValidator = WorktreeBranchNameValidator {
                error(
                    "git check should not run when target branch matches the detached base commit-ish",
                )
            },
            baseCommitIsh = "abc123",
        )

        assertEquals(
            "Target branch must differ from the base commit-ish",
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "abc123",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertFalse(isCreateWorktreeConfirmEnabled(validation))
    }

    @Test
    fun createWorktreeDialogAllowsDetachedTargetBranchMatchingBaseBranchLabel() {
        val validation = finishCreateWorktreeTargetBranchValidation(
            baseBranch = "feature/base-pr",
            targetBranch = "feature/base-pr",
            branchNameValidator = WorktreeBranchNameValidator { true },
            baseCommitIsh = "abc123",
        )

        assertEquals(
            null,
            createWorktreeTargetBranchValidationMessage(
                targetBranch = "feature/base-pr",
                validation = validation,
            ),
        )
        assertFalse(validation.isCheckingGitRefFormat)
        assertTrue(isCreateWorktreeConfirmEnabled(validation))
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
    fun rebaseActionIsDisabledWhileSetupIsInProgress() {
        assertFalse(
            isWorktreeRebaseEnabled(
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun rebaseActionIsDisabledWhileRebaseIsInProgress() {
        assertFalse(
            isWorktreeRebaseEnabled(
                setupStatus = null,
                isArchiving = false,
                isRebasing = true,
            ),
        )
    }

    @Test
    fun rebaseActionIsEnabledWhenWorktreeIsIdle() {
        assertTrue(isWorktreeRebaseEnabled(setupStatus = null, isArchiving = false, isRebasing = false))
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

    private fun rebaseNeededRow(): LocalWorktreeRowState = worktreeRow(needsRebase = true)

    private fun upToDateRow(): LocalWorktreeRowState = worktreeRow(needsRebase = false)

    private fun worktreeRow(needsRebase: Boolean): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            needsRebase = needsRebase,
        ),
        setupStatus = null,
        isArchiving = false,
    )
}
