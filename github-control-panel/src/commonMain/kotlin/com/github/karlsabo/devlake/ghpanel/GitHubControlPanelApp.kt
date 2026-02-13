package com.github.karlsabo.devlake.ghpanel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.github.karlsabo.devlake.ghpanel.component.ErrorDialog
import com.github.karlsabo.devlake.ghpanel.screen.GitHubControlPanelScreen
import com.github.karlsabo.devlake.ghpanel.viewmodel.GitHubControlPanelViewModel
import com.github.karlsabo.git.GitWorktreeService
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.system.DesktopLauncherService
import com.github.karlsabo.tools.gitHubConfigPath
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.SystemFileSystem
import org.jetbrains.compose.resources.painterResource

private val logger = KotlinLogging.logger {}

@Composable
fun GitHubControlPanelApp(onExitApplication: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    var isDisplayErrorDialog by remember { mutableStateOf(false) }
    var viewModel by remember { mutableStateOf<GitHubControlPanelViewModel?>(null) }

    LaunchedEffect(Unit) {
        logger.info { "Loading configuration" }
        try {
            val config = loadGitHubControlPanelConfig()
            val gitHubApiConfig = loadGitHubConfig(gitHubConfigPath)
            val gitHubApi = GitHubRestApi(gitHubApiConfig)
            val gitWorktreeService = GitWorktreeService()
            val desktopLauncher = DesktopLauncherService()

            viewModel = GitHubControlPanelViewModel(
                gitHubApi = gitHubApi,
                gitWorktreeApi = gitWorktreeService,
                desktopLauncher = desktopLauncher,
                config = config,
            )
            isLoading = false
            logger.info { "Config loaded: $config" }
        } catch (error: Exception) {
            errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                    "Please update the configuration file:\n$gitHubControlPanelConfigPath."
            logger.error { errorMessage }
            if (!SystemFileSystem.exists(gitHubControlPanelConfigPath)) {
                saveGitHubControlPanelConfig(GitHubControlPanelConfig())
            }
            isDisplayErrorDialog = true
            isLoading = false
        }
    }

    if (!isLoading && isDisplayErrorDialog) {
        ErrorDialog(
            message = errorMessage,
            onDismiss = onExitApplication,
        )
        return
    }

    Window(
        onCloseRequest = onExitApplication,
        title = "Git Control Panel",
        icon = painterResource(Res.drawable.icon),
        visible = !isLoading && viewModel != null,
        state = rememberWindowState(
            width = 1400.dp,
            height = 900.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        viewModel?.let { vm ->
            GitHubControlPanelScreen(viewModel = vm)
        }
    }
}
