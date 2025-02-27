package com.github.karlsabo.devlake.tools


import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.github.karlsabo.devlake.accessor.PipelineAccessorDb
import com.github.karlsabo.devlake.accessor.Status
import com.github.karlsabo.devlake.createSummary
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.dto.DevLakeSummary
import com.github.karlsabo.devlake.dto.toSlackMarkup
import com.github.karlsabo.devlake.loadUserAndTeamConfig
import com.github.karlsabo.devlake.textSummarizerConfigPath
import com.github.karlsabo.ds.*
import com.github.karlsabo.text.*
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m2.markdownColor
import com.mikepenz.markdown.m2.markdownTypography
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

fun main() = application {
    var isDisplayErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var summaryConfig by remember { mutableStateOf(SummaryPublisherConfig()) }
    var textSummarizerConfig by remember {
        mutableStateOf<TextSummarizerOpenAiConfigNoSecrets?>(
            TextSummarizerOpenAiConfigNoSecrets("invalid")
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
        println("Loading configuration")
        try {
            summaryConfig = loadSummaryPublisherConfig()
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

        var slackMarkDown by remember { mutableStateOf("Loading summary") }
        val scope = rememberCoroutineScope()
        var summaryLast7Days: DevLakeSummary? by remember { mutableStateOf(null) }

        var isLoadingSummary by remember { mutableStateOf(true) }
        if (isConfigLoaded) {
            LaunchedEffect(Unit) {
                DataSourceManagerDb(dataSourceConfigNoSecrets!!.toDataSourceDbConfig()).use { dataSourceManager ->
                    summaryLast7Days = createSummary(
                        dataSourceManager.getOrCreateDataSource(),
                        TextSummarizerOpenAi(textSummarizerConfig!!.toTextSummarizerOpenAiConfig()),
                        summaryConfig.projects,
                        7.days,
                        loadUserAndTeamConfig()!!.users,
                        summaryConfig.summaryName,
                    )
                    slackMarkDown = summaryLast7Days?.toSlackMarkup() ?: "* Failed to load summary"
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
                                    slackMarkDown,
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
                    Box {
                        VerticalScrollbar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(scrollState) // Attach the same scroll state
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
                                        value = slackMarkDown,
                                        onValueChange = { slackMarkDown = it },
                                        label = { Text("Edit Slack Markdown (doesn't render accurately because the UI only supports Markdown") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Markdown(
                                        content = slackMarkDown,
                                        typography = markdownTypography(
                                            h1 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                                            h2 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                                            h3 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                                        ),
                                        colors = markdownColor(),
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
data class SlackMessage(val message: String)

suspend fun sendToZap(slackMessage: String, zapierSummaryUrl: String): Boolean {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
    }
    val response: HttpResponse = client.post(zapierSummaryUrl) {
        header(HttpHeaders.Referrer, "https://hooks.zapier.com")
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(SlackMessage.serializer(), SlackMessage(slackMessage)))
    }

    println("response=$response, body=${response.body<String>()}")
    client.close()
    return response.status.value >= 200 && response.status.value <= 299
}
