package com.github.karlsabo.devlake.ghpanel.state

import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.extractOwnerAndRepo

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
)

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

    val reviewSummaryText = "${reviewSummary.approvedCount}/${reviewSummary.requestedCount} approved"

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
        apiUrl = url ?: "",
    )
}
