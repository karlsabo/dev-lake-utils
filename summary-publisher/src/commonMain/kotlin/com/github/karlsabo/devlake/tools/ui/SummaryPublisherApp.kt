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
import com.github.karlsabo.devlake.tools.ui.components.errorDialog
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.saveGitHubConfig
import com.github.karlsabo.linear.config.LinearConfig
import com.github.karlsabo.linear.config.saveLinearConfig
import com.github.karlsabo.pagerduty.PagerDutyConfig
import com.github.karlsabo.pagerduty.savePagerDutyConfig
import com.github.karlsabo.system.DesktopAppBootstrapResult
import com.github.karlsabo.system.runDesktopAppBootstrap
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

private data class LoadedSummaryPublisherApp(
    val config: SummaryPublisherConfig,
    val dependencies: SummaryPublisherDependencies,
)

internal data class SummaryPublisherBootstrapTemplate(
    val path: Path,
    val missingMessage: String,
    val createIfMissing: () -> Unit,
)

@Composable
fun summaryPublisherApp(
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
        errorDialog(
            message = state.errorMessage,
            onDismiss = onExitApplication,
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
        summaryPublisherScreen(
            state = SummaryPublisherScreenState(
                topLevelSummary = state.topLevelSummary,
                projectSummaries = state.projectSummaries,
                publishState = SummaryPublisherPublishState(
                    buttonText = state.publishButtonText,
                    isButtonEnabled = state.publishButtonEnabled,
                    isLoadingSummary = state.isLoadingSummary,
                    isSendingSlackMessage = state.isSendingSlackMessage,
                ),
            ),
            actions = SummaryPublisherScreenActions(
                onTopLevelSummaryChange = { state.topLevelSummary = it },
                onProjectMessageChange = state::updateProjectMessage,
                onProjectDelete = state::removeProject,
                onPublishClick = {
                    scope.launch {
                        publishSummary(state)
                    }
                },
            ),
        )
    }
}

internal fun loadConfiguration(
    state: SummaryPublisherState,
    configFilePath: Path,
    loadConfig: (Path) -> SummaryPublisherConfig = ::loadSummaryPublisherConfig,
    loadDependencies: (SummaryPublisherConfig) -> SummaryPublisherDependencies = ::loadSummaryPublisherDependencies,
    buildErrorMessage: (Throwable) -> String = ::buildConfigurationErrorMessage,
) {
    when (
        val result = runDesktopAppBootstrap(
            logger = logger,
            description = "summary publisher configuration $configFilePath",
            load = {
                val config = loadConfig(configFilePath)
                LoadedSummaryPublisherApp(
                    config = config,
                    dependencies = loadDependencies(config),
                )
            },
            buildErrorMessage = buildErrorMessage,
        )
    ) {
        is DesktopAppBootstrapResult.Loaded -> {
            state.summaryConfig = result.value.config
            state.dependencies = result.value.dependencies
            state.errorMessage = ""
            state.isDisplayErrorDialog = false
            state.isConfigLoaded = true
            logger.info { "Summary config = ${state.summaryConfig}" }
        }

        is DesktopAppBootstrapResult.Failed -> {
            state.dependencies = null
            state.errorMessage = result.errorMessage
            state.isDisplayErrorDialog = true
            state.isConfigLoaded = false
        }
    }
}

internal fun buildConfigurationErrorMessage(
    error: Throwable,
    templates: List<SummaryPublisherBootstrapTemplate> = summaryPublisherBootstrapTemplates(),
    exists: (Path) -> Boolean = SystemFileSystem::exists,
): String {
    var message = "Failed to load configuration: $error."

    templates.forEach { template ->
        if (!exists(template.path)) {
            message += template.missingMessage
            template.createIfMissing()
        }
    }

    return message
}

internal fun summaryPublisherBootstrapTemplates(): List<SummaryPublisherBootstrapTemplate> = listOf(
    SummaryPublisherBootstrapTemplate(
        path = summaryPublisherConfigPath,
        missingMessage = "\nCreating new configuration.\n" +
            " Please update the configuration file:\n$summaryPublisherConfigPath.",
        createIfMissing = { saveSummaryPublisherConfig(SummaryPublisherConfig()) },
    ),
    SummaryPublisherBootstrapTemplate(
        path = textSummarizerConfigPath,
        missingMessage = "Please update the configuration file:\n$textSummarizerConfigPath.",
        createIfMissing = {
            saveTextSummarizerOpenAiNoSecrets(
                textSummarizerConfigPath,
                TextSummarizerOpenAiConfigNoSecrets(apiKeyFilePath = "password.txt"),
            )
        },
    ),
    SummaryPublisherBootstrapTemplate(
        path = linearConfigPath,
        missingMessage = "Please update the configuration file:\n$linearConfigPath.",
        createIfMissing = {
            saveLinearConfig(linearConfigPath, LinearConfig(tokenPath = "/path/to/linear-api-key.json"))
        },
    ),
    SummaryPublisherBootstrapTemplate(
        path = gitHubConfigPath,
        missingMessage = "Please update the configuration file:\n$gitHubConfigPath.",
        createIfMissing = {
            saveGitHubConfig(gitHubConfigPath, GitHubConfig("/path/to/github-token.json"))
        },
    ),
    SummaryPublisherBootstrapTemplate(
        path = pagerDutyConfigPath,
        missingMessage = "Please update the configuration file:\n$pagerDutyConfigPath.",
        createIfMissing = {
            savePagerDutyConfig(PagerDutyConfig("/path/to/pagerduty-api-key.json"), pagerDutyConfigPath)
        },
    ),
)

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
