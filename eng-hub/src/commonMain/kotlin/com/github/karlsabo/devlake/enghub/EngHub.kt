package com.github.karlsabo.devlake.enghub

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
import com.github.karlsabo.devlake.enghub.component.ErrorDialog
import com.github.karlsabo.devlake.enghub.screen.EngHubScreen
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.SystemFileSystem
import org.jetbrains.compose.resources.painterResource

private val logger = KotlinLogging.logger {}

@Composable
fun EngHub(onExitApplication: () -> Unit) {
    var isLoadingConfiguration by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    var isDisplayErrorDialog by remember { mutableStateOf(false) }
    var viewModel by remember { mutableStateOf<EngHubViewModel?>(null) }

    LaunchedEffect(Unit) {
        logger.info { "Loading configuration" }
        try {
            viewModel = loadEngHubViewModel()
            isLoadingConfiguration = false
            logger.info { "Configuration loaded" }
        } catch (error: Exception) {
            errorMessage = "Failed to load configuration: $error.\nCreating new configuration.\n" +
                    "Please update the configuration file:\n$engHubConfigPath."
            logger.error { errorMessage }
            if (!SystemFileSystem.exists(engHubConfigPath)) {
                saveEngHubConfig(EngHubConfig())
            }
            isDisplayErrorDialog = true
            isLoadingConfiguration = false
        }
    }

    if (!isLoadingConfiguration && isDisplayErrorDialog) {
        ErrorDialog(
            message = errorMessage,
            onDismiss = onExitApplication,
        )
        return
    }

    Window(
        onCloseRequest = onExitApplication,
        title = ENG_HUB_DISPLAY_NAME,
        icon = painterResource(Res.drawable.icon),
        visible = !isLoadingConfiguration && viewModel != null,
        state = rememberWindowState(
            width = 1400.dp,
            height = 900.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        viewModel?.let { vm ->
            EngHubScreen(viewModel = vm)
        }
    }
}
