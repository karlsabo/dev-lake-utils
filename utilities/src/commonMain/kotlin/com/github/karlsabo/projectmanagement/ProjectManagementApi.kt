package com.github.karlsabo.projectmanagement

import kotlinx.datetime.Instant

/**
 * A unified interface for project management operations that can be implemented
 * by different backends (Jira, Linear, etc.).
 *
 * This interface provides a common set of operations for working with issues,
 * comments, and milestones across different project management systems.
 */
interface ProjectManagementApi {
    /**
     * Retrieves issues by their keys/identifiers.
     *
     * @param issueKeys List of issue keys (e.g., "PROJ-123" for Jira, "LIN-456" for Linear)
     * @return List of issues matching the given keys
     */
    suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue>

    /**
     * Retrieves all child issues (recursively) for the given parent issue keys.
     *
     * @param issueKeys List of parent issue keys
     * @return List of all child issues
     */
    suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue>

    /**
     * Retrieves direct child issues (non-recursive) for a single parent issue.
     *
     * @param parentKey The parent issue key
     * @return List of direct child issues
     */
    suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue>

    /**
     * Retrieves recent comments for an issue.
     *
     * @param issueKey The issue key to get comments for
     * @param maxResults Maximum number of comments to return
     * @return List of comments, ordered by most recent first
     */
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment>

    /**
     * Retrieves issues resolved by a user within a date range.
     *
     * @param userId The user ID (platform-specific: Jira account ID, Linear user ID)
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @return List of resolved issues
     */
    suspend fun getIssuesResolved(userId: String, startDate: Instant, endDate: Instant): List<ProjectIssue>

    /**
     * Counts issues resolved by a user within a date range.
     *
     * @param userId The user ID (platform-specific)
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @return Count of resolved issues
     */
    suspend fun getIssuesResolvedCount(userId: String, startDate: Instant, endDate: Instant): UInt

    /**
     * Retrieves issues matching the given filter criteria.
     *
     * The filter is interpreted according to each backend's capabilities:
     * - Jira: Uses JQL with custom field filters for labels
     * - Linear: Uses GraphQL filters with native label support
     *
     * @param filter The filter criteria
     * @return List of matching issues
     */
    suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue>

    /**
     * Retrieves milestones for a project.
     *
     * In Jira, this returns Epics as milestones.
     * In Linear, this returns native ProjectMilestones.
     *
     * @param projectId The project identifier
     * @return List of milestones
     */
    suspend fun getMilestones(projectId: String): List<ProjectMilestone>

    /**
     * Retrieves issues associated with a milestone.
     *
     * In Jira, this returns child issues of an Epic.
     * In Linear, this returns issues assigned to a ProjectMilestone.
     *
     * @param milestoneId The milestone identifier (Epic key for Jira, milestone ID for Linear)
     * @return List of issues in the milestone
     */
    suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue>
}
