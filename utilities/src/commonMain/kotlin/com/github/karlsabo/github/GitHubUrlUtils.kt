package com.github.karlsabo.github

/**
 * Converts a GitHub API URL to its corresponding HTML URL.
 * Example: `https://api.github.com/repos/owner/repo/pulls/123` -> `https://github.com/owner/repo/pull/123`
 */
fun apiUrlToHtmlUrl(apiUrl: String): String = apiUrl
    .replace("api.github.com/repos", "github.com")
    .replace("/pulls/", "/pull/")

/**
 * Extracts the owner and repository name from a GitHub API URL.
 * Example: `https://api.github.com/repos/owner/repo/pulls/123` -> `Pair("owner", "repo")`
 */
fun extractOwnerAndRepo(apiUrl: String): Pair<String, String> {
    val regex = Regex("repos/([^/]+)/([^/]+)")
    val match = regex.find(apiUrl)
        ?: throw IllegalArgumentException("Cannot extract owner/repo from URL: $apiUrl")
    return Pair(match.groupValues[1], match.groupValues[2])
}
