package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubConfigStateTest {
    @Test
    fun concurrentUpdatesAreAppliedToTheLatestSavedConfig() = runTest {
        val writer = RecordingStateConfigWriter()
        val state = EngHubConfigState(EngHubConfig(gitHubAuthor = "octocat"), writer)
        val repository = LocalRepositoryConfig(path = "/workspace/dev-lake-utils")

        listOf(
            async { state.update { it.copy(gitHubAuthor = "hubot") } },
            async { state.update { it.copy(localRepositories = it.localRepositories + repository) } },
        ).awaitAll()

        assertEquals("hubot", state.current.gitHubAuthor)
        assertEquals(listOf(repository), state.current.localRepositories)
        assertEquals(state.current, writer.savedConfigs.last())
    }
}

private class RecordingStateConfigWriter : EngHubConfigWriter {
    val savedConfigs = mutableListOf<EngHubConfig>()

    override fun save(config: EngHubConfig) {
        savedConfigs += config
    }
}
