package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeBranchNameValidatorTest {
    @Test
    fun validate_acceptsGitValidBranchName() {
        val validator = WorktreeBranchNameValidator { branch -> branch == "feature/stacked-pr" }

        val result = validator.validate("feature/stacked-pr")

        assertTrue(result.isValid)
    }

    @Test
    fun validateWithoutGitRefFormatCheck_acceptsPlausibleBranchWithoutCallingGitChecker() {
        val validator = WorktreeBranchNameValidator { error("git ref format checker should not run") }

        val result = validator.validateWithoutGitRefFormatCheck("feature/stacked-pr")

        assertTrue(result.isValid)
    }

    @Test
    fun validate_rejectsWhitespaceBeforeGitRefFormatCheck() {
        var checkedBranch: String? = null
        val validator = WorktreeBranchNameValidator { branch ->
            checkedBranch = branch
            true
        }

        val result = validator.validate("feature/new dashboard")

        assertFalse(result.isValid)
        assertEquals(WorktreeBranchNameValidationFailure.WHITESPACE, result.failure)
        assertEquals(null, checkedBranch)
    }

    @Test
    fun validate_rejectsControlCharactersBeforeGitRefFormatCheck() {
        var checkedBranch: String? = null
        val validator = WorktreeBranchNameValidator { branch ->
            checkedBranch = branch
            true
        }

        val result = validator.validate("feature/bad\u0007branch")

        assertFalse(result.isValid)
        assertEquals(WorktreeBranchNameValidationFailure.CONTROL_CHARACTER, result.failure)
        assertEquals(null, checkedBranch)
    }

    @Test
    fun validate_rejectsC1ControlCharactersBeforeGitRefFormatCheck() {
        var checkedBranch: String? = null
        val validator = WorktreeBranchNameValidator { branch ->
            checkedBranch = branch
            true
        }

        val result = validator.validate("feature/bad\u009Fbranch")

        assertFalse(result.isValid)
        assertEquals(WorktreeBranchNameValidationFailure.CONTROL_CHARACTER, result.failure)
        assertEquals(null, checkedBranch)
    }

    @Test
    fun validate_rejectsGitInvalidRefFormat() {
        val validator = WorktreeBranchNameValidator { false }

        val result = validator.validate("feature//stacked-pr")

        assertFalse(result.isValid)
        assertEquals(WorktreeBranchNameValidationFailure.INVALID_GIT_REF_FORMAT, result.failure)
    }
}
