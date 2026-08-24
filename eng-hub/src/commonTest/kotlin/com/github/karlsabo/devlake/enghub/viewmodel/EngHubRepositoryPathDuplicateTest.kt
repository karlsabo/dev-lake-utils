package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.system.OsFamily
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubRepositoryPathDuplicateTest {
    @Test
    fun macOsCaseAndLexicalAliasAddIsRejectedWithoutPersistenceOrRuntimeRefresh() = runTest {
        val original = LocalRepositoryConfig(path = "/workspace/api")
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(original)))
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer, configState, repositoryPathOsFamily = OsFamily.MACOS)
        val alias = "/WORKSPACE/other/../API"

        viewModel.localRepositorySettings.updateDraft(alias)
        viewModel.localRepositorySettings.add()
        runCurrent()

        assertEquals(alias, viewModel.uiState.value.localRepositoryDraft)
        assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, viewModel.uiState.value.localRepositoryError)
        assertEquals(listOf(original), configState.current.localRepositories)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun macOsCaseAndLexicalAliasEditIsRejectedWithoutPersistenceOrRuntimeRefresh() = runTest {
        val repositories = listOf(
            LocalRepositoryConfig(path = "/workspace/api"),
            LocalRepositoryConfig(path = "/workspace/web"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = repositories))
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer, configState, repositoryPathOsFamily = OsFamily.MACOS)
        val alias = "/WORKSPACE/other/../WEB"

        viewModel.localRepositorySettings.updatePath(0, alias)
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(alias, viewModel.uiState.value.localRepositories.first().path)
        assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, viewModel.uiState.value.localRepositories.first().pathError)
        assertEquals(repositories, configState.current.localRepositories)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun flushUsesTheInjectedOsFamilyForRepositoryPathValidation() = runTest {
        val repositories = listOf(
            LocalRepositoryConfig(path = "/workspace/api"),
            LocalRepositoryConfig(path = "/workspace/web"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = repositories))
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer, configState, repositoryPathOsFamily = OsFamily.LINUX)
        val caseDistinctLinuxPath = "/WORKSPACE/WEB"

        viewModel.localRepositorySettings.updatePath(0, caseDistinctLinuxPath)
        viewModel.flushPendingEdits()

        assertEquals(null, viewModel.uiState.value.localRepositories.first().pathError)
        assertEquals(caseDistinctLinuxPath, configState.current.localRepositories.first().path)
        assertEquals(caseDistinctLinuxPath, writer.savedConfigs.single().localRepositories.first().path)
    }

    @Test
    fun lexicalAliasLocalRepositoryPathsRemainInTheAddDraftWithAnError() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(
            EngHubConfig(
                localRepositories = listOf(
                    LocalRepositoryConfig(path = "/workspace/api"),
                    LocalRepositoryConfig(path = "/"),
                ),
            ),
        )
        val viewModel = settingsViewModel(writer, configState)

        listOf("/workspace/./api", "/workspace/other/../api", "/workspace/../").forEach { alias ->
            viewModel.localRepositorySettings.updateDraft(alias)
            viewModel.localRepositorySettings.add()
            assertEquals(alias, viewModel.uiState.value.localRepositoryDraft)
            assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, viewModel.uiState.value.localRepositoryError)
        }

        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun addingARepositoryPreservesItsLexicallyRedundantRepresentation() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)
        val enteredPath = "/workspace/other/../api"

        viewModel.localRepositorySettings.updateDraft(enteredPath)
        viewModel.localRepositorySettings.add()
        runCurrent()

        assertEquals(enteredPath, writer.savedConfigs.single().localRepositories.single().path)
        assertEquals(enteredPath, viewModel.uiState.value.localRepositories.single().path)
    }

    @Test
    fun editingToLexicalAliasRepositoryPathsShowsAnErrorWithoutPersisting() = runTest {
        val writer = RecordingConfigWriter()
        val repositories = listOf(
            LocalRepositoryConfig(path = "/workspace/api"),
            LocalRepositoryConfig(path = "/workspace/web"),
            LocalRepositoryConfig(path = "/"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = repositories))
        val viewModel = settingsViewModel(writer, configState)

        listOf("/workspace/./web", "/workspace/other/../web", "/workspace/..").forEach { alias ->
            viewModel.localRepositorySettings.updatePath(0, alias)
            assertEquals(alias, viewModel.uiState.value.localRepositories.first().path)
            assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, viewModel.uiState.value.localRepositories.first().pathError)
        }

        advanceTimeBy(750.milliseconds)
        runCurrent()
        assertEquals(repositories, configState.current.localRepositories)
        assertTrue(writer.savedConfigs.isEmpty())
    }
}
