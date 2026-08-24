package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubRepositoryConfigRefreshTest {
    @Test
    fun committedRepositoryPathRefreshesTheCurrentRepositoryUi() = runBlocking {
        val originalRepository = LocalRepositoryConfig(
            path = "/workspace/old",
            setupCommands = listOf("direnv allow"),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(originalRepository),
        )

        viewModel.updateConfig { config ->
            config.copy(
                localRepositories = listOf(originalRepository.copy(path = "/workspace/new")),
            )
        }

        assertEquals("/workspace/new", viewModel.localRepositoriesStateFlow.value.single().path)
    }
}
