package com.github.karlsabo.devlake.ghpanel

import androidx.compose.ui.window.application

fun main() = application {
    GitHubControlPanelApp(onExitApplication = ::exitApplication)
}
