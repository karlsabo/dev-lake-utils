package com.github.karlsabo.github

data class GitHubRepositoryIdentity(
    val owner: String,
    val repository: String,
) {
    init {
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(repository.isNotBlank()) { "repository must not be blank" }
    }

    val fullName: String = "$owner/$repository"

    fun matches(repositoryFullName: String): Boolean = fullName.equals(repositoryFullName, ignoreCase = true)
}

fun parseGitHubRepositoryIdentity(remoteUrl: String): GitHubRepositoryIdentity? = githubRepositoryPath(remoteUrl.trim())
    ?.trimEnd('/')
    ?.removeSuffix(".git")
    ?.split('/')
    ?.takeIf { pathParts -> pathParts.size == 2 && pathParts.none(String::isBlank) }
    ?.let { pathParts ->
        GitHubRepositoryIdentity(owner = pathParts[0], repository = pathParts[1])
    }

private fun githubRepositoryPath(remoteUrl: String): String? {
    val urlMatch = GITHUB_URL.matchEntire(remoteUrl)
    if (urlMatch != null) return urlMatch.groupValues[1]
    return GITHUB_SCP_URL.matchEntire(remoteUrl)?.groupValues?.get(1)
}

private val GITHUB_URL = Regex(
    pattern = "^(?:https?://|ssh://(?:git@)?)github\\.com/(.+)$",
    option = RegexOption.IGNORE_CASE,
)
private val GITHUB_SCP_URL = Regex(
    pattern = "^(?:git@)?github\\.com:(.+)$",
    option = RegexOption.IGNORE_CASE,
)
