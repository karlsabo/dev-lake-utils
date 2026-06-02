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
import com.github.karlsabo.devlake.metrics.UserMetricPublisherState
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.model.toSlackMarkdown
import com.github.karlsabo.devlake.metrics.rememberUserMetricPublisherState
import com.github.karlsabo.devlake.metrics.saveUserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.service.SlackMessage
import com.github.karlsabo.devlake.metrics.ui.components.errorDialog
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
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger {}

@Composable
fun userMetricPublisherApp(onExitApplication: () -> Unit) {
    val state = rememberUserMetricPublisherState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loadConfiguration(state)
    }

    if (!state.isLoadingConfig && state.isDisplayErrorDialog) {
        errorDialog(
            message = state.errorMessage,
            onDismiss = onExitApplication,
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
        userMetricPublisherScreen(
            metricsPreviewText = state.metricsPreviewText,
            publishButtonText = state.publishButtonText,
            publishButtonEnabled = state.publishButtonEnabled,
            onPublishClick = {
                scope.launch {
                    publishMetrics(state)
                }
            },
        )
    }
}

internal data class UserMetricPublisherRuntime(
    val configFilePath: Path = userMetricPublisherConfigPath,
    val loadConfig: () -> UserMetricPublisherConfig = ::loadUserMetricPublisherConfig,
    val loadDependencies: (UserMetricPublisherConfig) -> UserMetricPublisherDependencies =
        ::loadUserMetricPublisherDependencies,
    val configFileStore: UserMetricPublisherConfigFileStore = UserMetricPublisherConfigFileStore(),
)

internal data class UserMetricPublisherConfigFileStore(
    val exists: (Path) -> Boolean = SystemFileSystem::exists,
    val saveDefault: (UserMetricPublisherConfig) -> Unit = ::saveUserMetricPublisherConfig,
)

internal class UserMetricPublisherUserNotFoundException(
    userId: String,
) : IllegalStateException("User not found: $userId")

internal fun loadConfiguration(
    state: UserMetricPublisherState,
    runtime: UserMetricPublisherRuntime = UserMetricPublisherRuntime(),
) {
    when (
        val result = runDesktopAppBootstrap(
            logger = logger,
            description = "configuration ${runtime.configFilePath}",
            load = runtime.loadConfig,
            buildErrorMessage = { error ->
                buildConfigurationErrorMessage(
                    error = error,
                    configFilePath = runtime.configFilePath,
                )
            },
        )
    ) {
        is DesktopAppBootstrapResult.Loaded -> loadDependencies(state, result.value, runtime)
        is DesktopAppBootstrapResult.Failed -> handleConfigurationFailure(state, result, runtime)
    }
}

private fun loadDependencies(
    state: UserMetricPublisherState,
    config: UserMetricPublisherConfig,
    runtime: UserMetricPublisherRuntime,
) {
    logger.info { "Loading configuration dependencies" }
    state.config = config
    runCatching {
        runtime.loadDependencies(config)
    }.onSuccess { dependencies ->
        state.dependencies = dependencies
        state.errorMessage = ""
        state.isDisplayErrorDialog = false
        state.isLoadingConfig = false
        logger.info { "Config = ${state.config}" }
    }.onFailure { error ->
        state.dependencies = null
        state.errorMessage = buildDependenciesErrorMessage(
            error = error,
            configFilePath = runtime.configFilePath,
        )
        logger.error(error) { state.errorMessage }
        state.isDisplayErrorDialog = true
        state.isLoadingConfig = false
    }
}

private fun handleConfigurationFailure(
    state: UserMetricPublisherState,
    result: DesktopAppBootstrapResult.Failed,
    runtime: UserMetricPublisherRuntime,
) {
    state.dependencies = null
    state.errorMessage = result.errorMessage
    logger.error { state.errorMessage }
    if (!runtime.configFileStore.exists(runtime.configFilePath)) {
        runtime.configFileStore.saveDefault(UserMetricPublisherConfig())
    }
    state.isDisplayErrorDialog = true
    state.isLoadingConfig = false
}

internal fun buildConfigurationErrorMessage(
    error: Exception,
    configFilePath: Path = userMetricPublisherConfigPath,
): String = "Failed to load configuration: $error.\nCreating new configuration.\n" +
    "Please update the configuration file:\n$configFilePath."

internal fun buildDependenciesErrorMessage(
    error: Throwable,
    configFilePath: Path = userMetricPublisherConfigPath,
): String = "Failed to load dependencies: $error.\n" +
    "Please update the configuration file:\n$configFilePath."

internal suspend fun loadMetrics(state: UserMetricPublisherState) {
    logger.info { "Loading metrics" }
    val dependencies = state.dependencies ?: return

    state.metrics = kotlinx.coroutines.coroutineScope {
        state.config.userIds.map { userId ->
            async(Dispatchers.IO) {
                val user = dependencies.usersConfig.users.firstOrNull { it.id == userId }
                    ?: throw UserMetricPublisherUserNotFoundException(userId)
                measureTimedValue {
                    dependencies.metricsService.createUserMetrics(
                        user,
                        state.config.organizationIds,
                        dependencies.projectManagementApi,
                        dependencies.gitHubApi,
                    )
                }.also {
                    logger.debug { "Time to load metrics for $userId: ${it.duration}" }
                }.value
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

internal suspend fun publishMetrics(state: UserMetricPublisherState) {
    logger.info { "Publishing metrics" }
    val messagePublisherService = requireNotNull(state.dependencies?.messagePublisherService) {
        "User metric publisher dependencies must be loaded before publishing metrics"
    }

    state.onPublishStarted()

    var success = true
    state.metrics.forEach { metric ->
        val message = SlackMessage(
            userEmail = metric.email,
            message = buildMetricMessage(metric, state.config.metricInformationPostfix),
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

internal fun buildMetricMessage(
    metric: com.github.karlsabo.devlake.metrics.model.UserMetrics,
    metricInformationPostfix: String,
): String = "📢 *Weekly PR & Issue Summary* 🚀 (${metric.userId})\n" +
    metric.toSlackMarkdown() +
    "\n" +
    metricInformationPostfix
