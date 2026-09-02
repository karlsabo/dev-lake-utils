package com.github.karlsabo.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubRepositoryIdentityTest {
    @Test
    fun parsesCommonGitHubHttpsAndSshOriginUrls() {
        val expected = GitHubRepositoryIdentity("owner", "dev-lake-utils")

        assertEquals(expected, parseGitHubRepositoryIdentity("https://github.com/owner/dev-lake-utils.git"))
        assertEquals(expected, parseGitHubRepositoryIdentity("git@github.com:owner/dev-lake-utils.git"))
        assertEquals(expected, parseGitHubRepositoryIdentity("ssh://git@github.com/owner/dev-lake-utils.git"))
    }

    @Test
    fun rejectsNonGitHubAndMalformedOriginUrls() {
        assertNull(parseGitHubRepositoryIdentity("https://gitlab.com/owner/dev-lake-utils.git"))
        assertNull(parseGitHubRepositoryIdentity("https://github.com/owner"))
        assertNull(parseGitHubRepositoryIdentity("https://github.com/owner/repo/extra"))
    }

    @Test
    fun repositoryFullNameMatchingIsCaseInsensitive() {
        val identity = GitHubRepositoryIdentity("Owner", "Dev-Lake-Utils")

        assertTrue(identity.matches("owner/dev-lake-utils"))
    }
}
