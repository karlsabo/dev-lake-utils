package com.github.karlsabo.devlake.metrics


import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.github.karlsabo.devlake.accessor.*
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.isFinishedToday
import com.github.karlsabo.devlake.tools.UserMetricPublisherConfig
import com.github.karlsabo.devlake.tools.loadUserMetricPublisherConfig
import com.github.karlsabo.devlake.tools.saveUserMetricPublisherConfig
import com.github.karlsabo.devlake.tools.userMetricPublisherConfigPath
import com.github.karlsabo.ds.DataSourceManager
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.measureTime

fun main() = application {
    var isLoadingConfig by remember { mutableStateOf(true) }
    var isDisplayErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var config by remember { mutableStateOf(UserMetricPublisherConfig()) }
    var dataSourceManager by remember { mutableStateOf<DataSourceManager?>(null) }

    LaunchedEffect(Unit) {
        println("Loading configuration")
        try {
            config = loadUserMetricPublisherConfig()
            val dataSourceConfig = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)!!
            dataSourceManager = DataSourceManagerDb(dataSourceConfig.toDataSourceDbConfig())
            isLoadingConfig = false
        } catch (error: Exception) {
            errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                    "Please update the configuration file:\n${userMetricPublisherConfigPath}."
            saveUserMetricPublisherConfig(UserMetricPublisherConfig())
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

    var isPipelineLoading by remember { mutableStateOf(true) }
    var isShowPipelineError by remember { mutableStateOf(false) }
    var pipelineErrorMessage by remember { mutableStateOf("Pipeline is out of date") }

    if (!isLoadingConfig) {
        LaunchedEffect(Unit) {
            val pipelineAccessor = PipelineAccessorDb(dataSourceManager!!.getOrCreateDataSource())
            val latestPipelines = pipelineAccessor.getPipelines(1, 0)
            if (latestPipelines.isEmpty() || !latestPipelines[0].isFinishedToday()) {
                pipelineErrorMessage = if (latestPipelines.isEmpty()) {
                    "No pipelines found"
                } else {
                    "Pipeline is out of date: ${latestPipelines[0].beganAt?.toLocalDateTime(TimeZone.currentSystemDefault())} ${TimeZone.currentSystemDefault()}, status: ${latestPipelines[0].status}"
                }
                isShowPipelineError = true
            }
        }
        isPipelineLoading = false
    }

    if (!isLoadingConfig && !isPipelineLoading && isShowPipelineError) {
        DialogWindow(
            title = "Error, DevLake pipeline is out of date",
            onCloseRequest = { isShowPipelineError = false },
            visible = isShowPipelineError,
        ) {
            Surface {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Text(text = pipelineErrorMessage, style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { isShowPipelineError = false }) {
                        Text("Continue")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = ::exitApplication) {
                        Text("Exit")
                    }
                }
            }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metric Publisher",
        visible = !isLoadingConfig && !isShowPipelineError && !isPipelineLoading,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        var publishButtonText by remember { mutableStateOf("Publish to Slack") }
        var publishButtonEnabled by remember { mutableStateOf(true) }
        val scrollState = rememberScrollState()

        var isLoadingMetrics by remember { mutableStateOf(true) }
        var metrics by remember { mutableStateOf(mutableListOf<UserMetrics>()) }
        var metricsPreviewText by remember { mutableStateOf("Loading...") }
        val scope = rememberCoroutineScope()

        if (!isLoadingConfig && !isShowPipelineError && !isPipelineLoading) {
            LaunchedEffect(Unit) {
                println("Loading metrics")
                val jobs = config.userIds.map { userId ->
                    async(Dispatchers.IO) {
                        measureTime {
                            val userMetrics = createUserMetrics(userId, dataSourceManager!!)
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
                isLoadingMetrics = false
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
        setBody(Json.encodeToString(SlackMessage.serializer(), slackMessage))
    }

    println("response=$response, body=${response.body<String>()}")
    client.close()
    return response.status.value >= 200 && response.status.value <= 299
}

// karlfixme also track issues created, pr comments, pr reviews
@Serializable
data class UserMetrics(
    val userId: String,
    val pullRequestsPastWeek: List<PullRequest>,
    val pullRequestsYearToDay: List<PullRequest>,
    val issuesClosedLastWeek: List<Issue>,
    val issuesClosedYearToDate: List<Issue>,
)

fun UserMetrics.toSlackMarkdown(): String {
    val numberOfWeeksThisYear = ((System.now() - (System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        .run { Instant.parse("${year}-01-01T00:00:00Z") })).inWholeDays / 7).toInt()

    val builder = StringBuilder()
    builder.append("📌 *Merged PRs*\n")
    builder.append("• *Past week:* `${pullRequestsPastWeek.size}`\n")
    builder.append("• *Year to Date:* `${pullRequestsYearToDay.size}`. _Expectation ~${(numberOfWeeksThisYear * 2.5).toInt()} (2-3 per week)_\n")
    builder.append("\n")
    builder.append("📌 *Issues Closed*\n")
    builder.append("• *Past week:* `${issuesClosedLastWeek.size}`\n")
    builder.append("• *Year to date:* `${issuesClosedYearToDate.size}`. _Expectation ~${(numberOfWeeksThisYear * 2.5).toInt()} (2-3 per week)_\n")
    builder.append("\n━━━━━━━━━━━━━━━━━━\n")

    builder.append("\n")
    builder.append("🔍 *Details:*\n")
    builder.append("🔹 *Merged PRs:*\n")
    pullRequestsPastWeek.forEach {
        builder.append("• <${it.url}|${it.pullRequestKey}> ${it.title}\n")
    }

    builder.append("\n")
    builder.append("🔹 *Issues Closed:*\n")
    issuesClosedLastWeek.forEach {
        builder.append("• <${it.url}|${it.issueKey}> ${it.title}\n")
    }
    return builder.toString()
}

fun createUserMetrics(userId: String, dataSourceManager: DataSourceManager): UserMetrics {
    val startOfThisYear = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        .run { Instant.parse("${year}-01-01T00:00:00Z") }

    val pullRequestsPastWeek = mutableListOf<PullRequest>()
    val pullRequestsYearToDate = mutableListOf<PullRequest>()
    val issuesClosedPastWeek = mutableListOf<Issue>()
    val issuesClosedYearToDate = mutableListOf<Issue>()
    val pullRequestAccessor = PullRequestAccessorDb(dataSourceManager.getOrCreateDataSource())
    val issueAccessor = IssueAccessorDb(dataSourceManager.getOrCreateDataSource())
    val userAccountAccessor = UserAccountAccessorDb(dataSourceManager.getOrCreateDataSource())
        val userAccounts = userAccountAccessor.getUserAccountByUserId(userId)
        userAccounts.forEach { userAccount ->
            pullRequestsPastWeek.addAll(
                pullRequestAccessor.getPullRequestsByAuthorIdAndAfterMergedDate(
                    userAccount.accountId,
                    System.now().minus(7.days),
                )
            )
            pullRequestsYearToDate.addAll(
                pullRequestAccessor.getPullRequestsByAuthorIdAndAfterMergedDate(
                    userAccount.accountId,
                    startOfThisYear,
                )
            )
            issuesClosedPastWeek.addAll(
                issueAccessor.getIssuesByAssigneeIdAndAfterResolutionDate(
                    userAccount.accountId,
                    System.now().minus(7.days)
                )
            )
            issuesClosedYearToDate.addAll(
                issueAccessor.getIssuesByAssigneeIdAndAfterResolutionDate(
                    userAccount.accountId,
                    startOfThisYear
                )
            )
    }
    return UserMetrics(
        userId,
        pullRequestsPastWeek,
        pullRequestsYearToDate,
        issuesClosedPastWeek,
        issuesClosedYearToDate,
    )
}
