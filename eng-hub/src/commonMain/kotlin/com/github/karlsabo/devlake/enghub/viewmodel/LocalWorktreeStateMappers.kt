package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.GitWorktreeApi

internal fun GitWorktreeApi.listLocalWorktreeUiStates(
    repoRootPath: String,
): List<LocalWorktreeUiState> = listWorktrees(repoRootPath).toLocalWorktreeUiStates(repoRootPath)
