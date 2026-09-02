package com.github.karlsabo.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubBranchReferenceTest {
    @Test
    fun parsesQualifiedBranchAndPreservesBranchSlashes() {
        assertEquals(
            GitHubBranchReference(
                repository = GitHubRepositoryIdentity("owner", "dev-lake-utils"),
                branch = "feature/shared/nested",
            ),
            parseGitHubBranchReference("owner/dev-lake-utils:feature/shared/nested"),
        )
    }

    @Test
    fun acceptsOnlyQualifiedBranchSyntax() {
        listOf(
            "feature/shared",
            "owner/dev-lake-utils#123",
            "https://github.com/owner/dev-lake-utils/pull/123",
            "owner/dev-lake-utils:",
            "owner:feature/shared",
            "owner/repo/extra:feature/shared",
        ).forEach { input -> assertNull(parseGitHubBranchReference(input), input) }
    }
}
