package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.dto.User
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.config.loadJiraConfig
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.isCompleted
import com.github.karlsabo.projectmanagement.isIssueOrBug
import com.github.karlsabo.tools.jiraConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.measureTime

private const val START_YEAR = 2025
private const val DESCRIPTION_TRUNCATE_THRESHOLD = 100
private const val DESCRIPTION_MAX_LENGTH = 200
private const val PERCENTAGE_SCALE = 100

private class UserEpicsWithIssuesDemoArgumentException(
    message: String,
) : IllegalArgumentException(message)

fun main(args: Array<String>): Unit = runBlocking {
    val userId = parseUserId(args)
    val jiraApi = JiraRestApi(loadJiraConfig(jiraConfigPath))

    println("Finding epics and their issues for user: $userId")

    val executionTime = measureTime {
        printContributedParents(userId, jiraApi)
    }

    println("\nExecution time: $executionTime")
}

private fun parseUserId(args: Array<String>): String = args.find { it.startsWith("--user=") }
    ?.substringAfter("=")
    ?: throw UserEpicsWithIssuesDemoArgumentException("No --user=username provided")

private suspend fun printContributedParents(userId: String, jiraApi: JiraRestApi) {
    val startDate = LocalDateTime(START_YEAR, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC)
    val userIssues = jiraApi.getIssuesResolved(
        User(id = userId, name = userId, jiraId = userId),
        startDate,
        Clock.System.now(),
    )
    println("Found ${userIssues.size} issues assigned to user")

    if (userIssues.isEmpty()) {
        println("No issues found for user $userId")
        return
    }

    val parents = loadParentIssues(jiraApi, userIssues)
    println("# Parents the user contributed to:")
    printParents(jiraApi, parents, userIssues.groupBy { it.parentKey }, startDate)
}

private suspend fun loadParentIssues(
    jiraApi: JiraRestApi,
    userIssues: List<ProjectIssue>,
): List<ProjectIssue> {
    val allParentIssues = mutableSetOf<ProjectIssue>()
    val processedIssueKeys = mutableSetOf<String>()
    var parentIssueKeys = nextParentIssueKeys(userIssues, processedIssueKeys)

    while (parentIssueKeys.isNotEmpty()) {
        processedIssueKeys.addAll(parentIssueKeys)
        val parentIssues = jiraApi.getIssues(parentIssueKeys)
        allParentIssues.addAll(parentIssues)
        parentIssueKeys = nextParentIssueKeys(parentIssues, processedIssueKeys)
    }

    return allParentIssues
        .filter { !it.isIssueOrBug() }
        .sortedBy { it.completedAt ?: it.createdAt }
}

private fun nextParentIssueKeys(
    issues: List<ProjectIssue>,
    processedIssueKeys: Set<String>,
): List<String> = issues
    .mapNotNull { it.parentKey }
    .filter { it !in processedIssueKeys }
    .distinct()

private suspend fun printParents(
    jiraApi: JiraRestApi,
    parents: List<ProjectIssue>,
    userIssuesByParentKey: Map<String?, List<ProjectIssue>>,
    startDate: kotlin.time.Instant,
) {
    if (parents.isEmpty()) {
        println("No parents found for this user")
        return
    }

    parents.sortedBy { it.issueType }.forEach { parentIssue ->
        val contribution = parentContribution(jiraApi, parentIssue, userIssuesByParentKey)
        if (contribution.userCompletedIssues.isEmpty()) return@forEach

        val type = parentIssue.issueType ?: "Unknown"
        val title = parentIssue.title ?: "Untitled"
        val date = parentIssue.completedAt ?: parentIssue.createdAt
        println("* [$type] (${parentIssue.key}) $date $title ")
        println(contribution.summaryLine())

        contribution.userCompletedIssues.forEach { issue ->
            printCompletedIssue(issue, startDate)
        }
    }
}

private suspend fun parentContribution(
    jiraApi: JiraRestApi,
    parentIssue: ProjectIssue,
    userIssuesByParentKey: Map<String?, List<ProjectIssue>>,
): ParentContribution {
    val allChildIssues = jiraApi.getChildIssues(listOf(parentIssue.key))
        .filter { it.isIssueOrBug() }
    val totalCompletedCount = allChildIssues.count { it.isCompleted() }
    val userCompletedIssues = userIssuesByParentKey[parentIssue.key] ?: emptyList()
    val percentageByUser = if (totalCompletedCount > 0) {
        (userCompletedIssues.size.toDouble() / totalCompletedCount.toDouble() * PERCENTAGE_SCALE).toInt()
    } else {
        0
    }

    return ParentContribution(
        userCompletedIssues = userCompletedIssues,
        totalCompletedCount = totalCompletedCount,
        percentageByUser = percentageByUser,
    )
}

private data class ParentContribution(
    val userCompletedIssues: List<ProjectIssue>,
    val totalCompletedCount: Int,
    val percentageByUser: Int,
) {
    val userCompletedCount: Int = userCompletedIssues.size

    fun summaryLine(): String = "  * $userCompletedCount/$totalCompletedCount $percentageByUser%"
}

private fun printCompletedIssue(issue: ProjectIssue, startDate: kotlin.time.Instant) {
    issue.completedAt?.takeIf { it >= startDate } ?: return
    println("  * ${issue.key} - ${issue.title ?: "Untitled"}")
    printDescription(issue.description)
}

private fun printDescription(description: String?) {
    val truncatedDescription = description?.let {
        if (it.length > DESCRIPTION_TRUNCATE_THRESHOLD) {
            it.substring(0, min(DESCRIPTION_MAX_LENGTH, it.length)) + "..."
        } else {
            it
        }
    }

    println("    * Details:")
    println("      `````")
    truncatedDescription?.split(Regex("""\R+"""))?.forEach {
        println("      $it")
    }
    println("      `````")
}
