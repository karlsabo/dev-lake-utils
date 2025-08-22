package com.github.karlsabo.devlake.tools


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
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
import com.github.karlsabo.Credentials
import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.GitHubApiRestConfig
import com.github.karlsabo.github.GitHubConfig
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.loadGitHubConfig
import com.github.karlsabo.github.saveGitHubConfig
import com.github.karlsabo.jira.JiraApiRestConfig
import com.github.karlsabo.jira.JiraConfig
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.jira.saveJiraConfig
import com.github.karlsabo.pagerduty.PagerDutyApiRestConfig
import com.github.karlsabo.pagerduty.PagerDutyConfig
import com.github.karlsabo.pagerduty.PagerDutyRestApi
import com.github.karlsabo.pagerduty.loadPagerDutyConfig
import com.github.karlsabo.pagerduty.savePagerDutyConfig
import com.github.karlsabo.text.TextSummarizerOpenAi
import com.github.karlsabo.text.TextSummarizerOpenAiConfigNoSecrets
import com.github.karlsabo.text.loadTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.saveTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.toTextSummarizerOpenAiConfig
import com.github.karlsabo.tools.ProjectSummary
import com.github.karlsabo.tools.createSummary
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.jiraConfigPath
import com.github.karlsabo.tools.lenientJson
import com.github.karlsabo.tools.loadUsersConfig
import com.github.karlsabo.tools.pagerDutyConfigPath
import com.github.karlsabo.tools.textSummarizerConfigPath
import com.github.karlsabo.tools.toSlackMarkup
import com.github.karlsabo.tools.toVerboseSlackMarkdown
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
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days

data class ProjectSummaryHolder(val projectSummary: ProjectSummary, val message: String)

fun main(args: Array<String>) = application {
    val configParameter = args.find { it.startsWith("--config=") }?.substringAfter("=")
    val configFilePath: Path = configParameter?.let { Path(configParameter) } ?: summaryPublisherConfigPath

    var isDisplayErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var summaryConfig by remember { mutableStateOf(SummaryPublisherConfig()) }
    var textSummarizerConfig by remember {
        mutableStateOf<TextSummarizerOpenAiConfigNoSecrets?>(
            TextSummarizerOpenAiConfigNoSecrets("invalid")
        )
    }
    var jiraConfig by remember {
        mutableStateOf(
            JiraApiRestConfig(
                Credentials("username", "password"),
                "company.atlassian.net"
            )
        )
    }
    var gitHubConfig by remember { mutableStateOf(GitHubApiRestConfig("token")) }
    var pagerDutyConfig by remember { mutableStateOf(PagerDutyApiRestConfig("apiKey")) }
    var usersConfig by remember { mutableStateOf(UsersConfig(emptyList())) }

    var isConfigLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        println("Loading configuration $configFilePath")
        try {
            summaryConfig = loadSummaryPublisherConfig(configFilePath)
            textSummarizerConfig = loadTextSummarizerOpenAiNoSecrets(textSummarizerConfigPath)
            jiraConfig = loadJiraConfig(jiraConfigPath)
            gitHubConfig = loadGitHubConfig(gitHubConfigPath)
            pagerDutyConfig = loadPagerDutyConfig(pagerDutyConfigPath)
            usersConfig = loadUsersConfig()!!
        } catch (error: Exception) {
            println("Error loading summary config $error")
            errorMessage = "Failed to load configuration: $error."
            if (!SystemFileSystem.exists(summaryPublisherConfigPath)) {
                errorMessage += "\nCreating new configuration.\n Please update the configuration file:\n${summaryPublisherConfigPath}."
                saveSummaryPublisherConfig(SummaryPublisherConfig())
            }
            if (!SystemFileSystem.exists(textSummarizerConfigPath)) {
                errorMessage += "Please update the configuration file:\n${textSummarizerConfigPath}."
                saveTextSummarizerOpenAiNoSecrets(
                    textSummarizerConfigPath,
                    TextSummarizerOpenAiConfigNoSecrets(apiKeyFilePath = "password.txt"),
                )
            }
            if (!SystemFileSystem.exists(jiraConfigPath)) {
                errorMessage += "Please update the configuration file:\n${jiraConfigPath}."
                saveJiraConfig(jiraConfigPath, JiraConfig("domain", "username", "/path/to/jira-api-key.json"))
            }
            if (!SystemFileSystem.exists(gitHubConfigPath)) {
                errorMessage += "Please update the configuration file:\n${gitHubConfigPath}."
                saveGitHubConfig(gitHubConfigPath, GitHubConfig("/path/to/github-token.json"))
            }
            if (!SystemFileSystem.exists(pagerDutyConfigPath)) {
                errorMessage += "Please update the configuration file:\n${pagerDutyConfigPath}."
                savePagerDutyConfig(PagerDutyConfig("/path/to/pagerduty-api-key.json"), pagerDutyConfigPath)
            }
            isDisplayErrorDialog = true
        }
        isConfigLoaded = true
        println("Summary config = $summaryConfig")
    }

    if (isDisplayErrorDialog) {
        DialogWindow(
            onCloseRequest = ::exitApplication,
            title = "Error",
            visible = isDisplayErrorDialog,
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

    println("Config = $summaryConfig")

    var projectSummaries by remember { mutableStateOf(listOf<ProjectSummaryHolder>()) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Summary Publisher",
        visible = isConfigLoaded,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        var publishButtonText by remember { mutableStateOf("Publish to Slack") }
        var publishButtonEnabled by remember { mutableStateOf(true) }
        val scrollState = rememberScrollState()

        var isSendingSlackMessage by remember { mutableStateOf(false) }

        var topLevelSummary by remember { mutableStateOf("Loading summary") }
        val scope = rememberCoroutineScope()
        var summaryLast7Days: MultiProjectSummary? by remember { mutableStateOf(null) }

        var isLoadingSummary by remember { mutableStateOf(true) }
        if (isConfigLoaded) {
            LaunchedEffect(Unit) {
                summaryLast7Days = createSummary(
                    JiraRestApi(jiraConfig),
                    GitHubRestApi(gitHubConfig),
                    summaryConfig.gitHubOrganizationIds,
                    if (summaryConfig.pagerDutyServiceIds.isNotEmpty()) PagerDutyRestApi(pagerDutyConfig) else null,
                    summaryConfig.pagerDutyServiceIds,
                    TextSummarizerOpenAi(textSummarizerConfig!!.toTextSummarizerOpenAiConfig()),
                    summaryConfig.projects,
                    7.days,
                    usersConfig.users,
                    summaryConfig.miscUserIds.map { userId -> usersConfig.users.first { it.id == userId } },
                    summaryConfig.summaryName,
                    summaryConfig.isMiscellaneousProjectIncluded,
                )
                val slackSummary =
                    if (summaryConfig.isTerseSummaryUsed) summaryLast7Days?.toTerseSlackMarkup() else summaryLast7Days?.toSlackMarkup()
                topLevelSummary = slackSummary ?: "* Failed to generate a summary"
                projectSummaries
                projectSummaries = summaryLast7Days?.projectSummaries?.map {
                    val message =
                        if (it.project.isVerboseMilestones) it.toVerboseSlackMarkdown() else it.toSlackMarkup()
                    ProjectSummaryHolder(it, message)
                } ?: emptyList()
                isLoadingSummary = false
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
                                isSendingSlackMessage = true
                                val success = sendToZap(
                                    ZapierProjectSummary(topLevelSummary, projectSummaries.map { it.message }),
                                    summaryConfig.zapierSummaryUrl
                                )
                                isSendingSlackMessage = false
                                publishButtonText = if (success) "Message sent!" else "Failed to send message"
                            }
                        },
                        enabled = publishButtonEnabled && !isLoadingSummary,
                    ) {
                        if (isSendingSlackMessage) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text(publishButtonText)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    ) {
                        TextField(
                            value = topLevelSummary,
                            onValueChange = { topLevelSummary = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        )

                        projectSummaries.forEachIndexed { index, summaryHolder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = summaryHolder.message,
                                    onValueChange = { newValue ->
                                        projectSummaries = projectSummaries.toMutableList().also {
                                            it[index] = it[index].copy(message = newValue)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        projectSummaries =
                                            projectSummaries.toMutableList().also { it.removeAt(index) }
                                    },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("Delete")
                                }
                            }

                        }
//                        LazyColumn {
//                            itemsIndexed(
//                                items = projectSummaries,
//                                key = { _, summary -> summary.projectSummary.project.id }
//                            ) { index, summaryHolder ->
//                                Row(
//                                    modifier = Modifier
//                                        .fillMaxWidth()
//                                        .padding(horizontal = 16.dp, vertical = 8.dp),
//                                    verticalAlignment = Alignment.CenterVertically
//                                ) {
//                                    TextField(
//                                        value = summaryHolder.message,
//                                        onValueChange = { newValue ->
//                                            projectSummaries = projectSummaries.toMutableList().also {
//                                                it[index] = it[index].copy(message = newValue)
//                                            }
//                                        },
//                                        modifier = Modifier.weight(1f),
//                                    )
//                                    Button(
//                                        onClick = {
//                                            projectSummaries =
//                                                projectSummaries.toMutableList().also { it.removeAt(index) }
//                                        },
//                                        modifier = Modifier.padding(start = 8.dp)
//                                    ) {
//                                        Text("Delete")
//                                    }
//                                }
//                            }
//                        }
                    }
                }
            }
        }
    }
}

@Serializable
data class ZapierProjectSummary(val message: String, val projectMessages: List<String>)

suspend fun sendToZap(zapierProjectSummary: ZapierProjectSummary, zapierSummaryUrl: String): Boolean {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
    }
    val response: HttpResponse = client.post(zapierSummaryUrl) {
        header(HttpHeaders.Referrer, "https://hooks.zapier.com")
        contentType(ContentType.Application.Json)
        setBody(lenientJson.encodeToString(ZapierProjectSummary.serializer(), zapierProjectSummary))
    }

    println("response=$response, body=${response.body<String>()}")
    client.close()
    return response.status.value >= 200 && response.status.value <= 299
}
