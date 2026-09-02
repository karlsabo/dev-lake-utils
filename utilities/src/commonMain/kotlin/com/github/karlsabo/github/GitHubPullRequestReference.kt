package com.github.karlsabo.github

data class GitHubPullRequestReference(
    val number: Int,
    val repository: GitHubRepositoryIdentity? = null,
)

fun parseGitHubPullRequestReference(input: String): GitHubPullRequestReference? {
    val value = input.trim()
    return parseUnqualifiedPullRequestReference(value)
        ?: parseQualifiedPullRequestReference(value)
        ?: parsePullRequestUrl(value)
}

private fun parseUnqualifiedPullRequestReference(value: String): GitHubPullRequestReference? {
    val numberText = value.removePrefix("#")
    if (value.startsWith("##") || numberText.isEmpty() || !numberText.all(Char::isDigit)) return null
    return numberText.toPullRequestNumber()?.let(::GitHubPullRequestReference)
}

private fun parseQualifiedPullRequestReference(value: String): GitHubPullRequestReference? {
    val match = QUALIFIED_PULL_REQUEST.matchEntire(value) ?: return null
    return pullRequestReference(match)
}

private fun parsePullRequestUrl(value: String): GitHubPullRequestReference? {
    val match = PULL_REQUEST_URL.matchEntire(value) ?: return null
    return pullRequestReference(match)
}

private fun pullRequestReference(match: MatchResult): GitHubPullRequestReference? {
    val number = match.groupValues[NUMBER_GROUP].toPullRequestNumber() ?: return null
    return GitHubPullRequestReference(
        number = number,
        repository = GitHubRepositoryIdentity(
            owner = match.groupValues[OWNER_GROUP],
            repository = match.groupValues[REPOSITORY_GROUP],
        ),
    )
}

private fun String.toPullRequestNumber(): Int? = toIntOrNull()?.takeIf { it > 0 }

private const val OWNER_GROUP = 1
private const val REPOSITORY_GROUP = 2
private const val NUMBER_GROUP = 3

private val QUALIFIED_PULL_REQUEST = Regex("^([^/#]+)/([^/#]+)#([0-9]+)$")
private val PULL_REQUEST_URL = Regex(
    pattern = "^https://github\\.com/([^/]+)/([^/]+)/pull/([0-9]+)$",
    option = RegexOption.IGNORE_CASE,
)
