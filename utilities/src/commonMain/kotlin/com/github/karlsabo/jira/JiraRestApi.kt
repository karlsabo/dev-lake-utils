package com.github.karlsabo.jira

import com.github.karlsabo.jira.config.JiraApiRestConfig
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import io.ktor.client.HttpClient
import kotlin.time.Instant

private const val JQL_PAGE_SIZE = 100

class JiraApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A client for interacting with the Jira REST API.
 *
 * This class provides an interface for executing Jira-related operations,
 * including querying issues using JQL and filtering issues by custom fields.
 *
 * The implementation adapts the functionality provided by the `ProjectManagementApi`
 * interface and uses specific integrations with Jira's APIs for additional capabilities.
 *
 * @constructor Creates a new instance of the JiraRestApi.
 *
 * @param clients Encapsulates the components required to interact with Jira, such as issue reading
 * functionalities and project management operations.
 *
 * Secondary constructors:
 * - Accepts a `JiraApiRestConfig` and optional `labelCustomFieldId` to configure the API client.
 * - Allows specifying an explicit `HttpClient` for custom HTTP transport.
 */
class JiraRestApi private constructor(
    private val clients: JiraClients,
) : ProjectManagementApi by clients.projectManagementApi {
    constructor(
        config: JiraApiRestConfig,
        labelCustomFieldId: String? = null,
    ) : this(createJiraClients(config, labelCustomFieldId, null))

    constructor(config: JiraApiRestConfig, httpClient: HttpClient) : this(createJiraClients(config, null, httpClient))

    /** Executes a JQL query and returns the matching issues. */
    suspend fun runJql(jql: String): List<ProjectIssue> = clients.issueReader.runJql(jql)

    /** Queries issues by a Jira custom field filter. */
    suspend fun getIssuesByCustomFieldFilter(
        issueTypes: List<String>,
        customFieldFilter: CustomFieldFilter,
        resolvedAfter: Instant? = null,
        resolvedBefore: Instant? = null,
    ): List<ProjectIssue> = clients.issueReader.getIssuesByCustomFieldFilter(
        issueTypes = issueTypes,
        customFieldFilter = customFieldFilter,
        resolvedAfter = resolvedAfter,
        resolvedBefore = resolvedBefore,
    )
}

private fun createJiraClients(
    config: JiraApiRestConfig,
    labelCustomFieldId: String?,
    clientOverride: HttpClient?,
): JiraClients {
    val httpApi = JiraHttpApi(config, clientOverride)
    val jqlSearch = JiraJqlSearchClient(
        domain = config.domain,
        httpApi = httpApi,
        pageSize = JQL_PAGE_SIZE,
    )
    val issueReader = JiraIssueReader(
        config = config,
        httpApi = httpApi,
        jqlSearch = jqlSearch,
        labelCustomFieldId = labelCustomFieldId,
    )
    val projectManagementApi = JiraProjectManagementClient(
        issueReader = issueReader,
        commentReader = JiraCommentReader(config, httpApi),
        milestoneReader = JiraMilestoneReader(jqlSearch),
    )
    return JiraClients(issueReader, projectManagementApi)
}

private data class JiraClients(
    val issueReader: JiraIssueReader,
    val projectManagementApi: ProjectManagementApi,
)
