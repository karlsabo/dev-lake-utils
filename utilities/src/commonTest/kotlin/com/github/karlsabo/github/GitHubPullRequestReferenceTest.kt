package com.github.karlsabo.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubPullRequestReferenceTest {
    @Test
    fun parsesExactlyTheSupportedPullRequestAliases() {
        val unqualified = GitHubPullRequestReference(number = 123)
        val qualified = GitHubPullRequestReference(
            number = 123,
            repository = GitHubRepositoryIdentity("owner", "dev-lake-utils"),
        )

        assertEquals(unqualified, parseGitHubPullRequestReference("123"))
        assertEquals(unqualified, parseGitHubPullRequestReference("#123"))
        assertEquals(qualified, parseGitHubPullRequestReference("owner/dev-lake-utils#123"))
        assertEquals(
            qualified,
            parseGitHubPullRequestReference("https://github.com/owner/dev-lake-utils/pull/123"),
        )
    }

    @Test
    fun rejectsUnsupportedAndMalformedAliases() {
        listOf(
            "pull/123",
            "dev-lake-utils#123",
            "owner/dev-lake-utils",
            "owner/dev-lake-utils:feature/shared",
            "http://github.com/owner/dev-lake-utils/pull/123",
            "https://api.github.com/repos/owner/dev-lake-utils/pulls/123",
            "https://github.com/owner/dev-lake-utils/pull/123/",
            "https://gitlab.com/owner/dev-lake-utils/pull/123",
            "#0",
            "123abc",
        ).forEach { input ->
            assertNull(parseGitHubPullRequestReference(input), input)
        }
    }
}
