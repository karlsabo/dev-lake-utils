package com.github.karlsabo.gitlab

import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import io.ktor.client.HttpClient

class GitLabRestApi private constructor(
    mergeRequestApi: GitLabMergeRequestApi,
    private val closeClient: () -> Unit,
) : GitLabMergeRequestApi by mergeRequestApi {
    constructor(config: GitLabApiRestConfig) : this(GitLabRestClient(config), config.apiBaseUrl)

    constructor(config: GitLabApiRestConfig, httpClient: HttpClient) : this(
        GitLabRestClient(config, httpClient),
        config.apiBaseUrl,
    )

    private constructor(restClient: GitLabRestClient, apiBaseUrl: String) : this(
        mergeRequestApi = GitLabMergeRequestRestApi(restClient, apiBaseUrl),
        closeClient = restClient::close,
    )

    override fun close() = closeClient()
}
