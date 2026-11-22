package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.extractOwnerAndRepo
import com.github.karlsabo.github.pullRequestApiUrl
import com.github.karlsabo.gitlab.GitLabMergeRequest
import com.github.karlsabo.gitlab.isDraft
import com.github.karlsabo.gitlab.repositoryPath

enum class PullRequestProvider(
    val label: String,
    val referencePrefix: String,
) {
    GitHub(label = "GitHub", referencePrefix = "#"),
    GitLab(label = "GitLab", referencePrefix = "!"),
}

data class PullRequestUiState(
    val number: Int,
    val title: String,
    val htmlUrl: String,
    val repositoryFullName: String,
    val owner: String,
    val repo: String,
    val isDraft: Boolean,
    val ciStatus: CiStatus,
    val ciSummaryText: String,
    val approvedCount: Int,
    val requestedCount: Int,
    val reviewSummaryText: String,
    val headRef: String?,
    val apiUrl: String,
    val provider: PullRequestProvider = PullRequestProvider.GitHub,
) {
    val referenceLabel: String
        get() = "${provider.referencePrefix}$number"
}

fun Issue.toPullRequestUiState(
    checkRunSummary: CheckRunSummary,
    reviewSummary: ReviewSummary,
    headRef: String?,
): PullRequestUiState {
    val repoUrl = repositoryUrl ?: ""
    val (owner, repo) = if (repoUrl.isNotEmpty()) extractOwnerAndRepo(repoUrl) else ("" to "")
    val fullName = if (owner.isNotEmpty()) "$owner/$repo" else ""

    val ciSummaryText = buildString {
        append("${checkRunSummary.passed}/${checkRunSummary.total} passed")
        if (checkRunSummary.failed > 0) append(", ${checkRunSummary.failed} failed")
        if (checkRunSummary.inProgress > 0) append(", ${checkRunSummary.inProgress} running")
    }

    val pendingReviewers = (reviewSummary.requestedCount - reviewSummary.approvedCount).coerceAtLeast(0)
    val reviewSummaryText = when {
        pendingReviewers == 1 && reviewSummary.requestedCount == 1 -> "waiting on 1 reviewer"
        pendingReviewers > 0 -> "waiting on $pendingReviewers/${reviewSummary.requestedCount} reviewers"
        else -> "${reviewSummary.approvedCount}/${reviewSummary.requestedCount} approved"
    }

    return PullRequestUiState(
        number = number,
        title = title,
        htmlUrl = htmlUrl,
        repositoryFullName = fullName,
        owner = owner,
        repo = repo,
        isDraft = draft,
        ciStatus = checkRunSummary.status,
        ciSummaryText = ciSummaryText,
        approvedCount = reviewSummary.approvedCount,
        requestedCount = reviewSummary.requestedCount,
        reviewSummaryText = reviewSummaryText,
        headRef = headRef,
        apiUrl = pullRequestApiUrl.orEmpty(),
    )
}

/**
 * Setup and CI/review enrichment are out of scope for the first GitLab slice, so the branch stays hidden from
 * worktree actions and the summary fields remain empty.
 */
fun GitLabMergeRequest.toPullRequestUiState(): PullRequestUiState {
    val fullName = repositoryPath()
    val namespaceSeparator = fullName.lastIndexOf('/')
    return PullRequestUiState(
        number = iid,
        title = title,
        htmlUrl = webUrl,
        repositoryFullName = fullName,
        owner = if (namespaceSeparator > 0) fullName.substring(0, namespaceSeparator) else "",
        repo = fullName.substringAfterLast('/'),
        isDraft = isDraft,
        ciStatus = CiStatus.PENDING,
        ciSummaryText = "",
        approvedCount = 0,
        requestedCount = 0,
        reviewSummaryText = "",
        headRef = null,
        apiUrl = "",
        provider = PullRequestProvider.GitLab,
    )
}
