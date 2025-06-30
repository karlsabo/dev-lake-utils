package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.gitHubConfigPath
import com.github.karlsabo.devlake.jiraConfigPath
import com.github.karlsabo.devlake.loadUserAndTeamConfig
import com.github.karlsabo.devlake.tools.loadUserMetricPublisherConfig
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.loadGitHubConfig
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlin.time.measureTime


fun main() {
    val jiraConfig = loadJiraConfig(jiraConfigPath)
    val gitHubConfig = loadGitHubConfig(gitHubConfigPath)
    runBlocking {
        val config = loadUserMetricPublisherConfig()
        val users = loadUserAndTeamConfig()
        val metrics = mutableListOf<UserMetrics>()
        val jobs = config.userIds.map { userId ->
            async(Dispatchers.IO) {
                val user = users!!.users.firstOrNull { it.id == userId }
                    ?: throw Exception("User not found: $userId in ${users.users}")
                measureTime {
                    val userMetrics = createUserMetrics(
                        user,
                        config.organizationIds,
                        JiraRestApi(jiraConfig),
                        GitHubRestApi(gitHubConfig)
                    )
                    synchronized(metrics) {
                        metrics.add(userMetrics)
                    }
                }.also {
                    println("Time to load metrics for ${userId}: $it")
                }
            }
        }
        jobs.joinAll()

        metrics.forEach {
            println(
                "📢 *Weekly PR & Issue Summary* 🚀 (${it.userId})\n"
                        + it.toSlackMarkdown()
                        + "\n"
                        + config.metricInformationPostfix
            )
        }
    }
}
