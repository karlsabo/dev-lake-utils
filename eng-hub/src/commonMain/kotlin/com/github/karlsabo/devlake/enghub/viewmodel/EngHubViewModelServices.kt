package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.system.DesktopLauncher
import me.tatarka.inject.annotations.Inject

class EngHubGitHubServices @Inject constructor(
    val api: GitHubApi,
    val notificationService: GitHubNotificationService,
)

class EngHubWorktreeServices @Inject constructor(
    val gitWorktreeApi: GitWorktreeApi,
    val worktreeSetupCoordinator: WorktreeSetupCoordinator,
    val directoryPicker: DirectoryPicker,
    val configWriter: EngHubConfigWriter,
)

class EngHubDesktopServices @Inject constructor(
    val launcher: DesktopLauncher,
)
