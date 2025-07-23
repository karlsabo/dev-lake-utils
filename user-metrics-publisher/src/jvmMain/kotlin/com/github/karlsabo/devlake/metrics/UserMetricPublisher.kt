package com.github.karlsabo.devlake.metrics

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.karlsabo.devlake.tools.UserMetricPublisherConfig
import com.github.karlsabo.devlake.tools.loadUserMetricPublisherConfig
import com.github.karlsabo.devlake.tools.saveUserMetricPublisherConfig
import com.github.karlsabo.devlake.tools.userMetricPublisherConfigPath
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.loadGitHubConfig
import com.github.karlsabo.jira.Issue
import com.github.karlsabo.jira.JiraApi
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.jiraConfigPath
import com.github.karlsabo.tools.lenientJson
import com.github.karlsabo.tools.loadUsersConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.measureTime
import com.github.karlsabo.github.Issue as GitHubIssue

fun main() = application {
    var isLoadingConfig by remember { mutableStateOf(true) }
    var isDisplayErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var config by remember { mutableStateOf(UserMetricPublisherConfig()) }
    var userAndTeamsConfig by remember { mutableStateOf<UsersConfig?>(null) }
    var jiraApi by remember { mutableStateOf<JiraApi?>(null) }
    var gitHubApi by remember { mutableStateOf<GitHubApi?>(null) }

    LaunchedEffect(Unit) {
        println("Loading configuration")
        try {
            config = loadUserMetricPublisherConfig()
            val jiraApiRestConfig = loadJiraConfig(jiraConfigPath)
            jiraApi = JiraRestApi(jiraApiRestConfig)
            val gitHubApiRestConfig = loadGitHubConfig(gitHubConfigPath)
            gitHubApi = GitHubRestApi(gitHubApiRestConfig)
            userAndTeamsConfig = loadUsersConfig()!!
            isLoadingConfig = false
        } catch (error: Exception) {
            errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                    "Please update the configuration file:\n${userMetricPublisherConfigPath}."
            java.lang.System.err.println(errorMessage)
            if (!SystemFileSystem.exists(userMetricPublisherConfigPath)) {
                saveUserMetricPublisherConfig(UserMetricPublisherConfig())
            }
            isDisplayErrorDialog = true
        }
        println("Config = $config")
    }

    if (!isLoadingConfig && isDisplayErrorDialog) {
        DialogWindow(
            onCloseRequest = ::exitApplication,
            title = "Error",
        ) {
            Surface {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Text(text = "Error", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = ::exitApplication) {
                        Text("Ok")
                    }
                }
            }
        }
        return@application
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metric Publisher",
        visible = !isLoadingConfig,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        var publishButtonText by remember { mutableStateOf("Publish to Slack") }
        var publishButtonEnabled by remember { mutableStateOf(true) }
        val scrollState = rememberScrollState()

        var metrics by remember { mutableStateOf(mutableListOf<UserMetrics>()) }
        var metricsPreviewText by remember { mutableStateOf("Loading...") }
        val scope = rememberCoroutineScope()

        if (!isLoadingConfig) {
            LaunchedEffect(Unit) {
                println("Loading metrics")
                val jobs = config.userIds.map { userId ->
                    async(Dispatchers.IO) {
                        measureTime {
                            val user = userAndTeamsConfig!!.users.firstOrNull { it.id == userId }
                                ?: throw Exception("User not found: $userId in ${userAndTeamsConfig!!.users}")
                            val userMetrics = createUserMetrics(user, config.organizationIds, jiraApi!!, gitHubApi!!)
                            synchronized(metrics) {
                                metrics.add(userMetrics)
                            }
                        }.also {
                            println("Time to load metrics for ${userId}: $it")
                        }
                    }
                }
                jobs.joinAll()

                metricsPreviewText = ""
                metrics.forEach { userMetrics ->
                    metricsPreviewText += "\n"
                    metricsPreviewText += "${userMetrics.userId}\n"
                    metricsPreviewText += userMetrics.toSlackMarkdown()
                    metricsPreviewText += "\n" + config.metricInformationPostfix
                }

                println("Metrics loaded")
            }
        }

        MaterialTheme {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Button(
                        onClick = {
                            publishButtonEnabled = false
                            scope.launch {
                                var success = true
                                metrics.forEach {
                                    success = success && sendToZap(
                                        SlackMessage(
                                            it.userId,
                                            "📢 *Weekly PR & Issue Summary* 🚀 (${it.userId})\n"
                                                    + it.toSlackMarkdown()
                                                    + "\n"
                                                    + config.metricInformationPostfix
                                        ),
                                        config.zapierMetricUrl
                                    )
                                }
                                publishButtonText = if (success) "Message sent!" else "Failed to send message"
                            }

                        },
                        enabled = publishButtonEnabled,
                    ) {
                        Text(publishButtonText)
                    }
                    Box {
                        VerticalScrollbar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(scrollState)
                        )

                        Column(
                            modifier = Modifier.verticalScroll(scrollState).padding(end = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    TextField(
                                        value = metricsPreviewText,
                                        onValueChange = { println("changed") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        readOnly = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Serializable
private data class SlackMessage(val userEmail: String, val message: String)

private suspend fun sendToZap(slackMessage: SlackMessage, zapierMetricUrl: String): Boolean {
    println("Sending to ${slackMessage.userEmail}")
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
    }
    val response: HttpResponse = client.post(zapierMetricUrl) {
        header(HttpHeaders.Referrer, "https://hooks.zapier.com")
        contentType(ContentType.Application.Json)
        setBody(lenientJson.encodeToString(SlackMessage.serializer(), slackMessage))
    }

    println("response=$response, body=${response.body<String>()}")
    client.close()
    return response.status.value >= 200 && response.status.value <= 299
}

// karlfixme also track issues created, pr comments, pr reviews
@Serializable
data class UserMetrics(
    val userId: String,
    val pullRequestsPastWeek: List<GitHubIssue>,
    val pullRequestsYearToDateCount: UInt,
    val issuesClosedLastWeek: List<Issue>,
    val issuesClosedYearToDateCount: UInt,
)

fun UserMetrics.toSlackMarkdown(): String {
    val numberOfWeeksThisYear = ((System.now() - (System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        .run { Instant.parse("${year}-01-01T00:00:00Z") })).inWholeDays / 7).toInt()

    val builder = StringBuilder()
    builder.append("📌 *Merged PRs*\n")
    builder.append("• *Past week:* `${pullRequestsPastWeek.size}`\n")
    builder.append("• *Year to Date:* `$pullRequestsYearToDateCount`. _Expectation ~${(numberOfWeeksThisYear * 2.5).toInt()} (2-3 per week)_\n")
    builder.append("\n")
    builder.append("📌 *Issues Closed*\n")
    builder.append("• *Past week:* `${issuesClosedLastWeek.size}`\n")
    builder.append("• *Year to date:* `$issuesClosedYearToDateCount`. _Expectation ~${(numberOfWeeksThisYear * 2.5).toInt()} (2-3 per week)_\n")
    builder.append("\n━━━━━━━━━━━━━━━━━━\n")

    builder.append("\n")
    builder.append("🔍 *Details:*\n")
    builder.append("🔹 *Merged PRs:*\n")
    pullRequestsPastWeek.forEach {
        builder.append("• <${it.htmlUrl}|${it.number}> ${it.title}\n")
    }

    builder.append("\n")
    builder.append("🔹 *Issues Closed:*\n")
    issuesClosedLastWeek.forEach {
        builder.append("• <${it.url}|${it.issueKey}> ${it.title}\n")
    }
    return builder.toString()
}

suspend fun createUserMetrics(
    user: User,
    organizationIds: List<String>,
    jiraApi: JiraApi,
    gitHubApi: GitHubApi,
): UserMetrics {
    val startOfThisYear = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        .run { Instant.parse("${year}-01-01T00:00:00Z") }

    val pullRequestsPastWeek = mutableListOf<GitHubIssue>()
    val issuesClosedPastWeek = mutableListOf<Issue>()

    pullRequestsPastWeek.addAll(
        gitHubApi.getMergedPullRequests(
            user.gitHubId!!,
            organizationIds,
            System.now().minus(7.days),
            System.now(),
        )
    )
    val prCountYtd = gitHubApi.getMergedPullRequestCount(
        user.gitHubId!!,
        organizationIds,
        startOfThisYear,
        System.now(),
    )

    issuesClosedPastWeek.addAll(
        jiraApi.getIssuesResolved(
            user.jiraId!!,
            System.now().minus(7.days),
            System.now(),
        )
    )
    val issuesCountYtd = jiraApi.getIssuesResolvedCount(
        user.jiraId!!,
        startOfThisYear,
        System.now(),
    )

    return UserMetrics(
        user.id,
        pullRequestsPastWeek,
        prCountYtd,
        issuesClosedPastWeek,
        issuesCountYtd,
    )
}
