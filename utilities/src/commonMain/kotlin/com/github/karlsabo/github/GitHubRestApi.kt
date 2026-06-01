package com.github.karlsabo.github

import com.github.karlsabo.github.config.GitHubApiRestConfig
import io.ktor.client.HttpClient

class GitHubRestApi private constructor(
    pullRequestSearchApi: GitHubPullRequestSearchApi,
    notificationApi: GitHubNotificationApi,
    pullRequestReviewApi: GitHubPullRequestReviewApi,
    pullRequestSummaryApi: GitHubPullRequestSummaryApi,
) : GitHubApi,
    GitHubPullRequestSearchApi by pullRequestSearchApi,
    GitHubNotificationApi by notificationApi,
    GitHubPullRequestReviewApi by pullRequestReviewApi,
    GitHubPullRequestSummaryApi by pullRequestSummaryApi {
    constructor(config: GitHubApiRestConfig) : this(GitHubRestClient(config))

    constructor(config: GitHubApiRestConfig, httpClient: HttpClient) : this(GitHubRestClient(config, httpClient))

    private constructor(restClient: GitHubRestClient) : this(
        pullRequestSearchApi = GitHubPullRequestSearchRestApi(restClient),
        notificationApi = GitHubNotificationRestApi(restClient),
        pullRequestReviewApi = GitHubPullRequestReviewRestApi(restClient),
        pullRequestSummaryApi = GitHubPullRequestSummaryRestApi(restClient),
    )
}
