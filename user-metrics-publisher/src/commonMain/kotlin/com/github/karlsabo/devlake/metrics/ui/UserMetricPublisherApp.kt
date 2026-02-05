package com.github.karlsabo.devlake.metrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.github.karlsabo.devlake.metrics.UserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.model.toSlackMarkdown
import com.github.karlsabo.devlake.metrics.rememberUserMetricPublisherState
import com.github.karlsabo.devlake.metrics.saveUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.service.MetricsService
import com.github.karlsabo.devlake.metrics.service.SlackMessage
import com.github.karlsabo.devlake.metrics.service.ZapierMetricService
import com.github.karlsabo.devlake.metrics.ui.components.ErrorDialog
import com.github.karlsabo.devlake.metrics.userMetricPublisherConfigPath
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.config.loadJiraConfig
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.jiraConfigPath
import com.github.karlsabo.tools.loadUsersConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.io.files.SystemFileSystem
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Composable
fun UserMetricPublisherApp(onExitApplication: () -> Unit) {
    val state = rememberUserMetricPublisherState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loadConfiguration(state, onExitApplication)
    }

    if (!state.isLoadingConfig && state.isDisplayErrorDialog) {
        ErrorDialog(
            message = state.errorMessage,
            onDismiss = onExitApplication
        )
        return
    }

    LaunchedEffect(state.isLoadingConfig) {
        if (!state.isLoadingConfig && !state.isDisplayErrorDialog) {
            loadMetrics(state)
        }
    }

    Window(
        onCloseRequest = onExitApplication,
        title = "Metric Publisher",
        visible = !state.isLoadingConfig,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        UserMetricPublisherScreen(
            metricsPreviewText = state.metricsPreviewText,
            publishButtonText = state.publishButtonText,
            publishButtonEnabled = state.publishButtonEnabled,
            onPublishClick = {
                scope.launch {
                    state.onPublishStarted()
                    var success = true
                    state.metrics.forEach { metric ->
                        val message = SlackMessage(
                            userEmail = metric.userId,
                            message = "📢 *Weekly PR & Issue Summary* 🚀 (${metric.userId})\n" +
                                    metric.toSlackMarkdown() +
                                    "\n" +
                                    state.config.metricInformationPostfix
                        )
                        success = success && ZapierMetricService.sendMessage(message, state.config.zapierMetricUrl)
                    }
                    state.onPublishCompleted(success)
                }
            }
        )
    }
}

private suspend fun loadConfiguration(
    state: com.github.karlsabo.devlake.metrics.UserMetricPublisherState,
    onExitApplication: () -> Unit,
) {
    logger.info { "Loading configuration" }
    try {
        state.config = loadUserMetricPublisherConfig()
        state.projectManagementApi = JiraRestApi(loadJiraConfig(jiraConfigPath))
        state.gitHubApi = GitHubRestApi(loadGitHubConfig(gitHubConfigPath))
        state.usersConfig = loadUsersConfig()!!
        state.isLoadingConfig = false
        logger.info { "Config = ${state.config}" }
    } catch (error: Exception) {
        state.errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                "Please update the configuration file:\n${userMetricPublisherConfigPath}."
        logger.error { state.errorMessage }
        if (!SystemFileSystem.exists(userMetricPublisherConfigPath)) {
            saveUserMetricPublisherConfig(UserMetricPublisherConfig())
        }
        state.isDisplayErrorDialog = true
    }
}

private suspend fun loadMetrics(state: com.github.karlsabo.devlake.metrics.UserMetricPublisherState) {
    logger.info { "Loading metrics" }
    val usersConfig = state.usersConfig ?: return
    val projectManagementApi = state.projectManagementApi ?: return
    val gitHubApi = state.gitHubApi ?: return

    val jobs = state.config.userIds.map { userId ->
        kotlinx.coroutines.coroutineScope {
            async(Dispatchers.IO) {
                measureTime {
                    val user = usersConfig.users.firstOrNull { it.id == userId }
                        ?: throw Exception("User not found: $userId")
                    val userMetrics = MetricsService.createUserMetrics(
                        user,
                        state.config.organizationIds,
                        projectManagementApi,
                        gitHubApi
                    )
                    state.addMetric(userMetrics)
                }.also {
                    logger.debug { "Time to load metrics for $userId: $it" }
                }
            }
        }
    }
    jobs.joinAll()

    state.metricsPreviewText = buildString {
        state.metrics.forEach { userMetrics ->
            appendLine()
            appendLine(userMetrics.userId)
            append(userMetrics.toSlackMarkdown())
            appendLine()
            append(state.config.metricInformationPostfix)
        }
    }

    logger.info { "Metrics loaded" }
}
