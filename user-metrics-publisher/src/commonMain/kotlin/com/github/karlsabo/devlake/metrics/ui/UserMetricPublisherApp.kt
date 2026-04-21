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
import com.github.karlsabo.devlake.metrics.UserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.model.toSlackMarkdown
import com.github.karlsabo.devlake.metrics.rememberUserMetricPublisherState
import com.github.karlsabo.devlake.metrics.saveUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.service.SlackMessage
import com.github.karlsabo.devlake.metrics.ui.components.ErrorDialog
import com.github.karlsabo.devlake.metrics.userMetricPublisherConfigPath
import com.github.karlsabo.system.DesktopAppBootstrapResult
import com.github.karlsabo.system.runDesktopAppBootstrap
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
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
    configFilePath: Path = userMetricPublisherConfigPath,
    loadConfig: () -> UserMetricPublisherConfig = ::loadUserMetricPublisherConfig,
    loadDependencies: (UserMetricPublisherConfig) -> UserMetricPublisherDependencies =
        ::loadUserMetricPublisherDependencies,
    configFileExists: (Path) -> Boolean = SystemFileSystem::exists,
    saveDefaultConfig: (UserMetricPublisherConfig) -> Unit = ::saveUserMetricPublisherConfig,
) {
    when (
        val result = runDesktopAppBootstrap(
            logger = logger,
            description = "configuration $configFilePath",
            load = loadConfig,
            buildErrorMessage = { error ->
                buildConfigurationErrorMessage(
                    error = error,
                    configFilePath = configFilePath,
                )
            },
        )
    ) {
        is DesktopAppBootstrapResult.Loaded -> {
            logger.info { "Loading configuration dependencies" }
            try {
                state.config = result.value
                state.dependencies = loadDependencies(result.value)
                state.errorMessage = ""
                state.isDisplayErrorDialog = false
                state.isLoadingConfig = false
                logger.info { "Config = ${state.config}" }
            } catch (error: Exception) {
                state.dependencies = null
                state.errorMessage = buildDependenciesErrorMessage(
                    error = error,
                    configFilePath = configFilePath,
                )
                logger.error(error) { state.errorMessage }
                state.isDisplayErrorDialog = true
                state.isLoadingConfig = false
            }
        }

        is DesktopAppBootstrapResult.Failed -> {
            state.dependencies = null
            state.errorMessage = result.errorMessage
            logger.error { state.errorMessage }
            if (!configFileExists(configFilePath)) {
                saveDefaultConfig(UserMetricPublisherConfig())
            }
            state.isDisplayErrorDialog = true
            state.isLoadingConfig = false
        }
    }
}

internal fun buildConfigurationErrorMessage(
    error: Exception,
    configFilePath: Path = userMetricPublisherConfigPath,
): String {
    return "Failed to load configuration: $error.\nCreating new configuration.\n" +
        "Please update the configuration file:\n$configFilePath."
}

internal fun buildDependenciesErrorMessage(
    error: Exception,
    configFilePath: Path = userMetricPublisherConfigPath,
): String {
    return "Failed to load dependencies: $error.\n" +
        "Please update the configuration file:\n$configFilePath."
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
                    userMetrics = dependencies.metricsService.createUserMetrics(
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
    val messagePublisherService = requireNotNull(state.dependencies?.messagePublisherService) {
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
            messagePublisherService.publishMessage(message)
        }.getOrElse { error ->
            logger.error(error) { "Failed to publish metrics for ${metric.userId}" }
            false
        }
        success = success && publishSucceeded
    }

    state.onPublishCompleted(success)
}
