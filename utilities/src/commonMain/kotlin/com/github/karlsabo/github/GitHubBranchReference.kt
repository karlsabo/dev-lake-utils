package com.github.karlsabo.github

data class GitHubBranchReference(
    val repository: GitHubRepositoryIdentity,
    val branch: String,
)

fun parseGitHubBranchReference(input: String): GitHubBranchReference? {
    val match = QUALIFIED_BRANCH.matchEntire(input.trim()) ?: return null
    return GitHubBranchReference(
        repository = GitHubRepositoryIdentity(
            owner = match.groupValues[OWNER_GROUP],
            repository = match.groupValues[REPOSITORY_GROUP],
        ),
        branch = match.groupValues[BRANCH_GROUP],
    )
}

private const val OWNER_GROUP = 1
private const val REPOSITORY_GROUP = 2
private const val BRANCH_GROUP = 3
private val QUALIFIED_BRANCH = Regex("^([^\\s/:#]+)/([^\\s/:#]+):([^\\s]+)$")
