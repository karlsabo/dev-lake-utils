package com.github.karlsabo.devlake.metrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.github.karlsabo.devlake.metrics.UserMetricMessagePublisher
import com.github.karlsabo.devlake.metrics.UserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.UserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.UserMetricPublisherPreviewDependencies
import com.github.karlsabo.devlake.metrics.defaultUserMetricMessagePublisher
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherPreviewDependencies
import com.github.karlsabo.devlake.metrics.model.toSlackMarkdown
import com.github.karlsabo.devlake.metrics.rememberUserMetricPublisherState
import com.github.karlsabo.devlake.metrics.saveUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.service.SlackMessage
import com.github.karlsabo.devlake.metrics.ui.components.ErrorDialog
import com.github.karlsabo.devlake.metrics.userMetricPublisherConfigPath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.io.files.SystemFileSystem
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Composable
fun UserMetricPublisherApp(onExitApplication: () -> Unit) {
    val state = rememberUserMetricPublisherState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loadConfiguration(state)
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
                    publishMetrics(state)
                }
            }
        )
    }
}

internal fun loadConfiguration(
    state: com.github.karlsabo.devlake.metrics.UserMetricPublisherState,
    loadConfig: () -> UserMetricPublisherConfig = ::loadUserMetricPublisherConfig,
    loadPreviewDependencies: () -> UserMetricPublisherPreviewDependencies =
        ::loadUserMetricPublisherPreviewDependencies,
    messagePublisherFactory: (UserMetricPublisherConfig) -> UserMetricMessagePublisher = ::defaultUserMetricMessagePublisher,
) {
    logger.info { "Loading configuration" }
    try {
        state.config = loadConfig()
        state.dependencies = UserMetricPublisherDependencies(
            previewDependencies = loadPreviewDependencies(),
            messagePublisher = messagePublisherFactory(state.config),
        )
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
        state.isLoadingConfig = false
    }
}

internal suspend fun loadMetrics(state: com.github.karlsabo.devlake.metrics.UserMetricPublisherState) {
    logger.info { "Loading metrics" }
    val dependencies = state.dependencies ?: return

    state.metrics = kotlinx.coroutines.coroutineScope {
        state.config.userIds.map { userId ->
            async(Dispatchers.IO) {
                lateinit var userMetrics: com.github.karlsabo.devlake.metrics.model.UserMetrics
                measureTime {
                    val user = dependencies.usersConfig.users.firstOrNull { it.id == userId }
                        ?: throw Exception("User not found: $userId")
                    userMetrics = dependencies.metricsBuilder.createUserMetrics(
                        user,
                        state.config.organizationIds,
                        dependencies.projectManagementApi,
                        dependencies.gitHubApi,
                    )
                }.also {
                    logger.debug { "Time to load metrics for $userId: $it" }
                }
                userMetrics
            }
        }
    }.awaitAll()

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

internal suspend fun publishMetrics(state: com.github.karlsabo.devlake.metrics.UserMetricPublisherState) {
    logger.info { "Publishing metrics" }
    val messagePublisher = requireNotNull(state.dependencies?.messagePublisher) {
        "User metric publisher dependencies must be loaded before publishing metrics"
    }

    state.onPublishStarted()

    var success = true
    state.metrics.forEach { metric ->
        val message = SlackMessage(
            userEmail = metric.email,
            message = "📢 *Weekly PR & Issue Summary* 🚀 (${metric.userId})\n" +
                    metric.toSlackMarkdown() +
                    "\n" +
                    state.config.metricInformationPostfix
        )
        val publishSucceeded = runCatching {
            messagePublisher.publishMessage(message)
        }.getOrElse { error ->
            logger.error(error) { "Failed to publish metrics for ${metric.userId}" }
            false
        }
        success = success && publishSucceeded
    }

    state.onPublishCompleted(success)
}
