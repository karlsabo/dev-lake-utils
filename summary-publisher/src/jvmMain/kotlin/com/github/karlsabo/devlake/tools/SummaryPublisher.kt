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
import com.github.karlsabo.devlake.ProjectSummary
import com.github.karlsabo.devlake.accessor.PipelineAccessorDb
import com.github.karlsabo.devlake.accessor.Status
import com.github.karlsabo.devlake.createSummary
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.dto.DevLakeSummary
import com.github.karlsabo.devlake.dto.toSlackMarkup
import com.github.karlsabo.devlake.dto.toTerseSlackMarkup
import com.github.karlsabo.devlake.jiraConfigPath
import com.github.karlsabo.devlake.loadUserAndTeamConfig
import com.github.karlsabo.devlake.textSummarizerConfigPath
import com.github.karlsabo.devlake.toSlackMarkup
import com.github.karlsabo.devlake.toVerboseSlackMarkdown
import com.github.karlsabo.ds.DataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.saveDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.jira.JiraApiRestConfig
import com.github.karlsabo.jira.JiraConfig
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.jira.saveJiraConfig
import com.github.karlsabo.text.TextSummarizerOpenAi
import com.github.karlsabo.text.TextSummarizerOpenAiConfigNoSecrets
import com.github.karlsabo.text.loadTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.saveTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.toTextSummarizerOpenAiConfig
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
import kotlinx.datetime.Clock.System
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

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
    var dataSourceConfigNoSecrets by remember {
        mutableStateOf<DataSourceDbConfigNoSecrets?>(
            DataSourceDbConfigNoSecrets(
                passwordFilePath = "invalid.txt"
            )
        )
    }
    var isConfigLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        println("Loading configuration $configFilePath")
        try {
            summaryConfig = loadSummaryPublisherConfig(configFilePath)
        } catch (error: Exception) {
            println("Error loading summary config $error")
            errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                "Please update the configuration file:\n${summaryPublisherConfigPath}."
            if (!SystemFileSystem.exists(summaryPublisherConfigPath)) {
                saveSummaryPublisherConfig(SummaryPublisherConfig())
            }
            isDisplayErrorDialog = true
        }
        if (!isDisplayErrorDialog) {
            try {
                dataSourceConfigNoSecrets = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)
            } catch (error: Exception) {
                println("Error loading datasource config $error")
                errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                    "Please update the configuration file:\n${devLakeDataSourceDbConfigPath}."
                if (!SystemFileSystem.exists(devLakeDataSourceDbConfigPath)) {
                    saveDataSourceDbConfigNoSecrets(
                        devLakeDataSourceDbConfigPath,
                        DataSourceDbConfigNoSecrets(passwordFilePath = "password.txt")
                    )
                }
                isDisplayErrorDialog = true
            }
            if (!isDisplayErrorDialog) {
                try {
                    textSummarizerConfig = loadTextSummarizerOpenAiNoSecrets(textSummarizerConfigPath)
                } catch (error: Exception) {
                    println("Error loading text summarizer config $error")
                    errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                        "Please update the configuration file:\n${textSummarizerConfigPath}."
                    if (!SystemFileSystem.exists(textSummarizerConfigPath)) {
                        saveTextSummarizerOpenAiNoSecrets(
                            textSummarizerConfigPath,
                            TextSummarizerOpenAiConfigNoSecrets(apiKeyFilePath = "password.txt")
                        )
                    }
                    isDisplayErrorDialog = true
                }
            }
            if (!isDisplayErrorDialog) {
                try {
                    jiraConfig = loadJiraConfig(jiraConfigPath)
                } catch (error: Exception) {
                    isDisplayErrorDialog = true
                    println("Error loading Jira config ${error.message}")
                    errorMessage =
                        "Failed to load Jira config: ${error.message}.\nCreating a new configuration.\nPlease update the configuration ${jiraConfigPath}"
                    if (!SystemFileSystem.exists(jiraConfigPath)) {
                        saveJiraConfig(
                            JiraConfig("example.atlassian.net", "username", "apiKeyPath"),
                            jiraConfigPath
                        )
                    }
                }
            }
        }

        isConfigLoaded = true
        println("Summary config = $summaryConfig")
        println("DataSource config = $dataSourceConfigNoSecrets")
    }

    if (isDisplayErrorDialog) {
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

    println("Config = $summaryConfig")

    var isPipelineLoading by remember { mutableStateOf(true) }
    var isShowPipelineError by remember { mutableStateOf(false) }
    var pipelineErrorMessage by remember { mutableStateOf("Pipeline is out of date") }

    var projectSummaries by remember { mutableStateOf(listOf<ProjectSummaryHolder>()) }

    LaunchedEffect(Unit) {
        DataSourceManagerDb(dataSourceConfigNoSecrets!!.toDataSourceDbConfig()).use { dataSourceManager ->
            val pipelineAccessor = PipelineAccessorDb(dataSourceManager.getOrCreateDataSource())
            val latestPipelines = pipelineAccessor.getPipelines(1, 0)
            if (latestPipelines.isEmpty()
                || latestPipelines[0].status != Status.TASK_COMPLETED
                || latestPipelines[0].beganAt!! < System.now().minus(24.hours)
            ) {
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

    if (isShowPipelineError) {
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
        title = "Summary Publisher",
        visible = !isShowPipelineError && !isPipelineLoading,
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
        var summaryLast7Days: DevLakeSummary? by remember { mutableStateOf(null) }

        var isLoadingSummary by remember { mutableStateOf(true) }
        if (isConfigLoaded) {
            LaunchedEffect(Unit) {
                DataSourceManagerDb(dataSourceConfigNoSecrets!!.toDataSourceDbConfig()).use { dataSourceManager ->
                    summaryLast7Days = createSummary(
                        dataSourceManager.getOrCreateDataSource(),
                        JiraRestApi(jiraConfig),
                        TextSummarizerOpenAi(textSummarizerConfig!!.toTextSummarizerOpenAiConfig()),
                        summaryConfig.projects,
                        7.days,
                        loadUserAndTeamConfig()!!.users,
                        summaryConfig.summaryName,
                        summaryConfig.isMiscellaneousProjectIncluded,
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
                    } ?: emptyList<ProjectSummaryHolder>()
                }
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
        setBody(Json.encodeToString(ZapierProjectSummary.serializer(), zapierProjectSummary))
    }

    println("response=$response, body=${response.body<String>()}")
    client.close()
    return response.status.value >= 200 && response.status.value <= 299
}
