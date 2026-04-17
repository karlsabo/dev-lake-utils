package com.github.karlsabo.devlake.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.github.karlsabo.devlake.tools.ProjectSummaryHolder
import com.github.karlsabo.devlake.tools.SummaryPublisherConfig
import com.github.karlsabo.devlake.tools.SummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.SummaryPublisherState
import com.github.karlsabo.devlake.tools.loadSummaryPublisherConfig
import com.github.karlsabo.devlake.tools.loadSummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.rememberSummaryPublisherState
import com.github.karlsabo.devlake.tools.saveSummaryPublisherConfig
import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.devlake.tools.summaryPublisherConfigPath
import com.github.karlsabo.devlake.tools.ui.components.ErrorDialog
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.saveGitHubConfig
import com.github.karlsabo.linear.config.LinearConfig
import com.github.karlsabo.linear.config.saveLinearConfig
import com.github.karlsabo.pagerduty.PagerDutyConfig
import com.github.karlsabo.pagerduty.savePagerDutyConfig
import com.github.karlsabo.text.TextSummarizerOpenAiConfigNoSecrets
import com.github.karlsabo.text.saveTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.tools.formatting.toSlackMarkup
import com.github.karlsabo.tools.formatting.toVerboseSlackMarkdown
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.linearConfigPath
import com.github.karlsabo.tools.pagerDutyConfigPath
import com.github.karlsabo.tools.textSummarizerConfigPath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

@Composable
fun SummaryPublisherApp(
    configFilePath: Path,
    onExitApplication: () -> Unit,
    loadDependencies: (SummaryPublisherConfig) -> SummaryPublisherDependencies = ::loadSummaryPublisherDependencies,
) {
    val state = rememberSummaryPublisherState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loadConfiguration(state, configFilePath, loadDependencies = loadDependencies)
    }

    if (state.isDisplayErrorDialog) {
        ErrorDialog(
            message = state.errorMessage,
            onDismiss = onExitApplication
        )
        return
    }

    LaunchedEffect(state.isConfigLoaded) {
        if (state.isConfigLoaded) {
            loadSummaryData(state)
        }
    }

    Window(
        onCloseRequest = onExitApplication,
        title = "Summary Publisher",
        visible = state.isConfigLoaded,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        SummaryPublisherScreen(
            topLevelSummary = state.topLevelSummary,
            onTopLevelSummaryChange = { state.topLevelSummary = it },
            projectSummaries = state.projectSummaries,
            onProjectMessageChange = state::updateProjectMessage,
            onProjectDelete = state::removeProject,
            publishButtonText = state.publishButtonText,
            publishButtonEnabled = state.publishButtonEnabled,
            isLoadingSummary = state.isLoadingSummary,
            isSendingSlackMessage = state.isSendingSlackMessage,
            onPublishClick = {
                scope.launch {
                    publishSummary(state)
                }
            }
        )
    }
}

internal fun loadConfiguration(
    state: SummaryPublisherState,
    configFilePath: Path,
    loadConfig: (Path) -> SummaryPublisherConfig = ::loadSummaryPublisherConfig,
    loadDependencies: (SummaryPublisherConfig) -> SummaryPublisherDependencies = ::loadSummaryPublisherDependencies,
) {
    logger.info { "Loading configuration $configFilePath" }
    try {
        state.summaryConfig = loadConfig(configFilePath)
        state.dependencies = loadDependencies(state.summaryConfig)
        state.isConfigLoaded = true
        logger.info { "Summary config = ${state.summaryConfig}" }
    } catch (error: Exception) {
        logger.error(error) { "Error loading summary config" }
        state.errorMessage = buildConfigurationErrorMessage(error)
        state.isDisplayErrorDialog = true
    }
}

private fun buildConfigurationErrorMessage(error: Exception): String {
    var message = "Failed to load configuration: $error."

    if (!SystemFileSystem.exists(summaryPublisherConfigPath)) {
        message += "\nCreating new configuration.\n Please update the configuration file:\n${summaryPublisherConfigPath}."
        saveSummaryPublisherConfig(SummaryPublisherConfig())
    }
    if (!SystemFileSystem.exists(textSummarizerConfigPath)) {
        message += "Please update the configuration file:\n${textSummarizerConfigPath}."
        saveTextSummarizerOpenAiNoSecrets(
            textSummarizerConfigPath,
            TextSummarizerOpenAiConfigNoSecrets(apiKeyFilePath = "password.txt"),
        )
    }
    if (!SystemFileSystem.exists(linearConfigPath)) {
        message += "Please update the configuration file:\n${linearConfigPath}."
        saveLinearConfig(linearConfigPath, LinearConfig(tokenPath = "/path/to/linear-api-key.json"))
    }
    if (!SystemFileSystem.exists(gitHubConfigPath)) {
        message += "Please update the configuration file:\n${gitHubConfigPath}."
        saveGitHubConfig(gitHubConfigPath, GitHubConfig("/path/to/github-token.json"))
    }
    if (!SystemFileSystem.exists(pagerDutyConfigPath)) {
        message += "Please update the configuration file:\n${pagerDutyConfigPath}."
        savePagerDutyConfig(PagerDutyConfig("/path/to/pagerduty-api-key.json"), pagerDutyConfigPath)
    }

    return message
}

internal suspend fun loadSummaryData(state: SummaryPublisherState) {
    val summaryBuilder = requireNotNull(state.dependencies?.summaryBuilder) {
        "Summary publisher dependencies must be loaded before loading summary data"
    }
    val config = state.summaryConfig
    val summaryLast7Days = summaryBuilder.createSummary()

    val slackSummary = if (config.isTerseSummaryUsed) {
        summaryLast7Days.toTerseSlackMarkup()
    } else {
        summaryLast7Days.toSlackMarkup()
    }

    state.topLevelSummary = slackSummary
    state.projectSummaries = summaryLast7Days.projectSummaries.map {
        val message = if (it.project.isVerboseMilestones) {
            it.toVerboseSlackMarkdown()
        } else {
            it.toSlackMarkup()
        }
        ProjectSummaryHolder(it, message)
    }

    state.isLoadingSummary = false
}

internal suspend fun publishSummary(state: SummaryPublisherState) {
    val summaryPublisher = requireNotNull(state.dependencies?.summaryPublisher) {
        "Summary publisher dependencies must be loaded before publishing summaries"
    }

    state.onPublishStarted()

    val summary = ZapierProjectSummary(
        message = state.topLevelSummary,
        projectMessages = state.projectSummaries.map { it.message },
    )
    val success = runCatching {
        summaryPublisher.publishSummary(summary)
    }.getOrElse { error ->
        logger.error(error) { "Failed to publish summary" }
        false
    }

    state.onPublishCompleted(success)
}
