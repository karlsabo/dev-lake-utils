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
import com.github.karlsabo.devlake.tools.SummaryPublisherState
import com.github.karlsabo.devlake.tools.loadSummaryPublisherConfig
import com.github.karlsabo.devlake.tools.rememberSummaryPublisherState
import com.github.karlsabo.devlake.tools.saveSummaryPublisherConfig
import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.devlake.tools.service.ZapierService
import com.github.karlsabo.devlake.tools.summaryPublisherConfigPath
import com.github.karlsabo.devlake.tools.ui.components.ErrorDialog
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.github.config.saveGitHubConfig
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.config.JiraConfig
import com.github.karlsabo.jira.config.loadJiraConfig
import com.github.karlsabo.jira.config.saveJiraConfig
import com.github.karlsabo.pagerduty.PagerDutyConfig
import com.github.karlsabo.pagerduty.PagerDutyRestApi
import com.github.karlsabo.pagerduty.loadPagerDutyConfig
import com.github.karlsabo.pagerduty.savePagerDutyConfig
import com.github.karlsabo.text.TextSummarizerOpenAi
import com.github.karlsabo.text.TextSummarizerOpenAiConfigNoSecrets
import com.github.karlsabo.text.loadTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.saveTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.toTextSummarizerOpenAiConfig
import com.github.karlsabo.tools.createSummary
import com.github.karlsabo.tools.formatting.toSlackMarkup
import com.github.karlsabo.tools.formatting.toVerboseSlackMarkdown
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.jiraConfigPath
import com.github.karlsabo.tools.loadUsersConfig
import com.github.karlsabo.tools.pagerDutyConfigPath
import com.github.karlsabo.tools.textSummarizerConfigPath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.days

private val logger = KotlinLogging.logger {}

@Composable
fun SummaryPublisherApp(
    configFilePath: Path,
    onExitApplication: () -> Unit,
) {
    val state = rememberSummaryPublisherState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loadConfiguration(state, configFilePath, onExitApplication)
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
                    state.onPublishStarted()
                    val summary = ZapierProjectSummary(
                        state.topLevelSummary,
                        state.projectSummaries.map { it.message }
                    )
                    val success = ZapierService.sendSummary(summary, state.summaryConfig.zapierSummaryUrl)
                    state.onPublishCompleted(success)
                }
            }
        )
    }
}

private suspend fun loadConfiguration(
    state: SummaryPublisherState,
    configFilePath: Path,
    onExitApplication: () -> Unit,
) {
    logger.info { "Loading configuration $configFilePath" }
    try {
        state.summaryConfig = loadSummaryPublisherConfig(configFilePath)
        state.textSummarizerConfig = loadTextSummarizerOpenAiNoSecrets(textSummarizerConfigPath)
        state.jiraConfig = loadJiraConfig(jiraConfigPath)
        state.gitHubConfig = loadGitHubConfig(gitHubConfigPath)
        state.pagerDutyConfig = loadPagerDutyConfig(pagerDutyConfigPath)
        state.usersConfig = loadUsersConfig()!!
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
    if (!SystemFileSystem.exists(jiraConfigPath)) {
        message += "Please update the configuration file:\n${jiraConfigPath}."
        saveJiraConfig(jiraConfigPath, JiraConfig("domain", "username", "/path/to/jira-api-key.json"))
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

private suspend fun loadSummaryData(state: SummaryPublisherState) {
    val config = state.summaryConfig
    val jiraConfig = state.jiraConfig ?: return
    val gitHubConfig = state.gitHubConfig ?: return
    val pagerDutyConfig = state.pagerDutyConfig
    val textSummarizerConfig = state.textSummarizerConfig ?: return

    val summaryLast7Days = createSummary(
        JiraRestApi(jiraConfig),
        GitHubRestApi(gitHubConfig),
        config.gitHubOrganizationIds,
        if (config.pagerDutyServiceIds.isNotEmpty() && pagerDutyConfig != null) {
            PagerDutyRestApi(pagerDutyConfig)
        } else null,
        config.pagerDutyServiceIds,
        TextSummarizerOpenAi(textSummarizerConfig.toTextSummarizerOpenAiConfig()),
        config.projects,
        7.days,
        state.usersConfig.users,
        config.miscUserIds.map { userId -> state.usersConfig.users.first { it.id == userId } },
        config.summaryName,
        config.isMiscellaneousProjectIncluded,
    )

    val slackSummary = if (config.isTerseSummaryUsed) {
        summaryLast7Days?.toTerseSlackMarkup()
    } else {
        summaryLast7Days?.toSlackMarkup()
    }

    state.topLevelSummary = slackSummary ?: "* Failed to generate a summary"
    state.projectSummaries = summaryLast7Days?.projectSummaries?.map {
        val message = if (it.project.isVerboseMilestones) {
            it.toVerboseSlackMarkdown()
        } else {
            it.toSlackMarkup()
        }
        ProjectSummaryHolder(it, message)
    } ?: emptyList()

    state.isLoadingSummary = false
}
