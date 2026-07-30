package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubSettingsViewModelTest {
    @Test
    fun changingGitHubAuthorUpdatesTheDraftAndPersistsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.updateGitHubAuthor("hubot")

        assertEquals("hubot", viewModel.uiState.value.gitHubAuthor)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf("hubot"), writer.savedConfigs.map(EngHubConfig::gitHubAuthor))
    }

    @Test
    fun changingPollingIntervalCommitsMillisecondsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.updatePollIntervalSeconds("300")

        assertEquals("300", viewModel.uiState.value.pollIntervalSeconds)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(300_000L), writer.savedConfigs.map(EngHubConfig::pollIntervalMs))
    }

    @Test
    fun aNewerDraftCannotBeOverwrittenByAnOlderDebounce() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.updateGitHubAuthor("robot")
        advanceTimeBy(500.milliseconds)
        viewModel.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(listOf("hubot"), writer.savedConfigs.map(EngHubConfig::gitHubAuthor))
    }

    @Test
    fun changingGitHubAuthorPreservesARepositoryAddedAfterSettingsLoaded() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(gitHubAuthor = "octocat"))
        val viewModel = settingsViewModel(writer, configState)
        val repository = LocalRepositoryConfig(path = "/workspace/dev-lake-utils")
        configState.current = configState.current.copy(localRepositories = listOf(repository))

        viewModel.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(listOf(repository), writer.savedConfigs.single().localRepositories)
    }

    private fun kotlinx.coroutines.test.TestScope.settingsViewModel(
        writer: EngHubConfigWriter,
        configState: MutableConfigState = MutableConfigState(EngHubConfig(gitHubAuthor = "octocat")),
    ) = EngHubSettingsViewModel(
        engHubConfig = configState.current,
        gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
        gitHubSecret = GitHubSecret(githubToken = "token"),
        updateConfig = { transform ->
            transform(configState.current).also { updatedConfig ->
                writer.save(updatedConfig)
                configState.current = updatedConfig
            }
        },
        coroutineScope = this,
    )
}

private class MutableConfigState(
    var current: EngHubConfig,
)

private class RecordingConfigWriter : EngHubConfigWriter {
    val savedConfigs = mutableListOf<EngHubConfig>()

    override fun save(config: EngHubConfig) {
        savedConfigs += config
    }
}
