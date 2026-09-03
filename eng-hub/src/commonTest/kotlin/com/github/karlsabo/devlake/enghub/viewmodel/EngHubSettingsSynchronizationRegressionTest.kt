package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY
import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriter
import com.github.karlsabo.github.config.LoadedGitHubConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubSettingsSynchronizationRegressionTest {
    @Test
    fun clearingConfiguredGitHubTokenPersistsBlankSecretAndDisablesGitHubAccess() = runTest {
        val secretWriter = SynchronizationSecretWriter()
        val committedAccess = mutableListOf<LoadedGitHubConfig>()
        val viewModel = synchronizationViewModel(
            secretWriter = secretWriter,
            callbacks = GitHubSynchronizationCallbacks(onAccessCommitted = committedAccess::add),
        )

        viewModel.gitHubTokenSettings.updateToken("")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        val clearedAccess = LoadedGitHubConfig(GitHubConfig(SECRET_PATH), GitHubSecret(""))
        assertEquals(listOf(Path(SECRET_PATH) to GitHubSecret("")), secretWriter.savedSecrets)
        assertEquals(listOf(clearedAccess), committedAccess)
        assertEquals("", viewModel.uiState.value.gitHubToken.value)
        assertFalse(viewModel.uiState.value.gitHubAccessReady)
        assertEquals(null, viewModel.uiState.value.gitHubTokenError)
    }

    @Test
    fun rejectedSecretAliasDoesNotWriteOrChangeRuntimeReadiness() = runTest {
        val secretWriter = SynchronizationSecretWriter()
        val committedAccess = mutableListOf<LoadedGitHubConfig>()
        val viewModel = synchronizationViewModel(
            secretWriter = secretWriter,
            callbacks = GitHubSynchronizationCallbacks(
                validateSecretPath = {
                    throw com.github.karlsabo.github.config.GitHubSecretWriteException("path aliases config")
                },
                onAccessCommitted = committedAccess::add,
            ),
        )

        viewModel.gitHubTokenSettings.updateToken("replacement")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertTrue(secretWriter.savedSecrets.isEmpty())
        assertTrue(committedAccess.isEmpty())
        assertEquals("token", viewModel.uiState.value.gitHubToken.value)
        assertTrue(viewModel.uiState.value.gitHubAccessReady)
        assertEquals(GITHUB_SECRET_SAVE_ERROR, viewModel.uiState.value.gitHubTokenError)
    }

    @Test
    fun firstTimeSecretPathPersistsWithAnEmptySecretAndSurvivesRestart() = runTest {
        val savedAccess = mutableListOf<Pair<Path, GitHubSecret>>()
        val viewModel = synchronizationViewModel(
            loadedGitHubConfig = LoadedGitHubConfig(GitHubConfig(""), GitHubSecret("")),
            saveGitHubAccess = { path, secret ->
                savedAccess += path to secret
                LoadedGitHubConfig(GitHubConfig(path.toString()), secret)
            },
        )

        viewModel.gitHubTokenSettings.updateSecretPath("/secrets/new-github.json")
        viewModel.flushPendingEdits()

        val (savedPath, savedSecret) = savedAccess.single()
        assertEquals(Path("/secrets/new-github.json"), savedPath)
        assertEquals(GitHubSecret(""), savedSecret)
        assertFalse(viewModel.uiState.value.gitHubAccessReady)
        assertEquals(null, viewModel.uiState.value.gitHubTokenError)

        val restarted = synchronizationViewModel(
            loadedGitHubConfig = LoadedGitHubConfig(GitHubConfig(savedPath.toString()), savedSecret),
        )
        assertEquals(savedPath.toString(), restarted.uiState.value.gitHubTokenPath)
        assertFalse(restarted.uiState.value.gitHubAccessReady)
    }

    @Test
    fun externalRepositoryCollisionRemainsVisibleAndRejectsPendingPathDraft() = runTest {
        val original = LocalRepositoryConfig(path = "/workspace/old", setupCommands = listOf("prepare"))
        val external = LocalRepositoryConfig(path = "/workspace/external", setupCommands = listOf("bootstrap"))
        val configState = SynchronizationConfigState(EngHubConfig(localRepositories = listOf(original)))
        val viewModel = synchronizationViewModel(configState = configState)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/other/../external")
        configState.current = configState.current.copy(localRepositories = listOf(original, external))
        runCurrent()
        advanceTimeBy(750.milliseconds)
        runCurrent()
        viewModel.flushPendingEdits()

        val repositories = viewModel.uiState.value.localRepositories
        assertEquals(listOf("/workspace/other/../external", "/workspace/external"), repositories.map { it.path })
        assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, repositories.first().pathError)
        assertEquals(external.setupCommands, repositories.last().setupCommands)
        assertEquals(listOf(original, external), configState.current.localRepositories)
    }

    @Test
    fun undoAfterExternalRepositoryReAddExpiresWithoutCorruptingLaterCommandTargets() = runTest {
        val original = LocalRepositoryConfig(path = "/workspace/api", setupCommands = listOf("prepare"))
        val configState = SynchronizationConfigState(EngHubConfig(localRepositories = listOf(original)))
        val viewModel = synchronizationViewModel(configState = configState)

        viewModel.localRepositorySettings.remove(0)
        runCurrent()
        assertTrue(configState.current.localRepositories.isEmpty())

        val external = LocalRepositoryConfig(path = "/workspace/other/../api", setupCommands = listOf("external"))
        configState.current = configState.current.copy(localRepositories = listOf(external))
        runCurrent()
        viewModel.localRepositorySettings.undoRemoval()
        viewModel.flushPendingEdits()

        viewModel.localRepositorySettings.updateDraft("/workspace/web")
        viewModel.localRepositorySettings.add()
        runCurrent()
        viewModel.setupCommandSettings.updateDraft(1, "bootstrap")
        viewModel.setupCommandSettings.add(1, 0)
        runCurrent()
        viewModel.setupCommandSettings.update(1, 0, "bootstrap --updated")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        viewModel.flushPendingEdits()

        assertEquals(listOf("external"), configState.current.localRepositories[0].setupCommands)
        assertEquals(listOf("bootstrap --updated"), configState.current.localRepositories[1].setupCommands)
        assertEquals(
            configState.current.localRepositories.map { it.path },
            viewModel.uiState.value.localRepositories.map { it.path },
        )
    }

    @Test
    fun committedOptimisticRepositoryAddKeepsOneRowAndPreservesOtherDrafts() = runTest {
        val original = LocalRepositoryConfig(path = "/workspace/original", setupCommands = listOf("prepare"))
        val configState = SynchronizationConfigState(
            EngHubConfig(localRepositories = listOf(original)),
            yieldAfterEmission = true,
        )
        val viewModel = synchronizationViewModel(configState = configState)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/original-draft")
        viewModel.localRepositorySettings.updateDraft("/workspace/added")
        viewModel.localRepositorySettings.add()
        runCurrent()

        assertEquals(
            listOf("/workspace/original-draft", "/workspace/added"),
            viewModel.uiState.value.localRepositories.map { it.path },
        )
        assertEquals(2, configState.current.localRepositories.size)
    }

    @Test
    fun committedOptimisticRepositoryRenameKeepsOneIdentityAndUnrelatedDraft() = runTest {
        val first = LocalRepositoryConfig(path = "/workspace/first", setupCommands = listOf("prepare"))
        val second = LocalRepositoryConfig(path = "/workspace/second", setupCommands = listOf("keep"))
        val configState = SynchronizationConfigState(
            EngHubConfig(localRepositories = listOf(first, second)),
            yieldAfterEmission = true,
        )
        val viewModel = synchronizationViewModel(configState = configState)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/renamed")
        advanceTimeBy(400.milliseconds)
        viewModel.localRepositorySettings.updatePath(1, "/workspace/second-draft")
        advanceTimeBy(350.milliseconds)
        runCurrent()

        assertEquals(
            listOf("/workspace/renamed", "/workspace/second-draft"),
            viewModel.uiState.value.localRepositories.map { it.path },
        )
        assertEquals(
            listOf("/workspace/renamed", "/workspace/second"),
            configState.current.localRepositories.map { it.path },
        )
    }

    @Test
    fun commandAddUsesCommittedRepositoryWhenPendingPathCollides() = runTest {
        val (viewModel, configState, external) = viewModelWithCollidingPathDraft()

        viewModel.setupCommandSettings.updateDraft(0, "added")
        viewModel.setupCommandSettings.add(0, 1)
        runCurrent()
        viewModel.flushPendingEdits()

        assertEquals(listOf("original", "added"), configState.current.localRepositories.first().setupCommands)
        assertEquals(external, configState.current.localRepositories.last())
    }

    @Test
    fun commandEditUsesCommittedRepositoryWhenPendingPathCollides() = runTest {
        val (viewModel, configState, external) = viewModelWithCollidingPathDraft()

        viewModel.setupCommandSettings.update(0, 0, "edited")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        viewModel.flushPendingEdits()

        assertEquals(listOf("edited"), configState.current.localRepositories.first().setupCommands)
        assertEquals(external, configState.current.localRepositories.last())
    }

    @Test
    fun commandRemoveUsesCommittedRepositoryWhenPendingPathCollides() = runTest {
        val (viewModel, configState, external) = viewModelWithCollidingPathDraft()

        viewModel.setupCommandSettings.remove(0, 0)
        runCurrent()
        viewModel.flushPendingEdits()

        assertTrue(configState.current.localRepositories.first().setupCommands.isEmpty())
        assertEquals(external, configState.current.localRepositories.last())
    }

    @Test
    fun alertTriageEditPreservesAnUnknownTemplateAddedByAnExternalConfigUpdate() = runTest {
        val configState = SynchronizationConfigState(
            EngHubConfig(
                llmTemplateValues = mapOf(ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to "old guidance"),
            ),
        )
        val viewModel = synchronizationViewModel(configState = configState)

        viewModel.llmTemplateSettings.updateAlertTriageWhereToLook("new guidance")
        configState.current = configState.current.copy(
            llmTemplateValues = configState.current.llmTemplateValues + ("UNKNOWN_TEMPLATE" to "keep me"),
        )
        runCurrent()
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(
            mapOf(
                ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to "new guidance",
                "UNKNOWN_TEMPLATE" to "keep me",
            ),
            configState.current.llmTemplateValues,
        )
        assertEquals("new guidance", viewModel.uiState.value.alertTriageWhereToLook)
    }

    @Test
    fun externallyAddedRepositoryAppearsWithoutOverwritingAPathDraft() = runTest {
        val original = LocalRepositoryConfig(path = "/workspace/old", setupCommands = listOf("prepare"))
        val external = LocalRepositoryConfig(path = "/workspace/external", setupCommands = listOf("bootstrap", "open"))
        val configState = SynchronizationConfigState(EngHubConfig(localRepositories = listOf(original)))
        val viewModel = synchronizationViewModel(configState = configState)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/new")
        configState.current = configState.current.copy(localRepositories = listOf(original, external))
        runCurrent()

        val repositories = viewModel.uiState.value.localRepositories
        assertEquals(listOf("/workspace/new", "/workspace/external"), repositories.map { it.path })
        assertEquals(null, repositories.first().pathError)
        assertEquals(external.setupCommands, repositories.last().setupCommands)
    }

    private fun TestScope.viewModelWithCollidingPathDraft(): CollidingRepositoryFixture {
        val original = LocalRepositoryConfig(path = "/workspace/old", setupCommands = listOf("original"))
        val external = LocalRepositoryConfig(path = "/workspace/external", setupCommands = listOf("external"))
        val configState = SynchronizationConfigState(EngHubConfig(localRepositories = listOf(original)))
        val viewModel = synchronizationViewModel(configState = configState)
        viewModel.localRepositorySettings.updatePath(0, "/workspace/external")
        configState.current = configState.current.copy(localRepositories = listOf(original, external))
        runCurrent()
        return CollidingRepositoryFixture(viewModel, configState, external)
    }

    private fun TestScope.synchronizationViewModel(
        configState: SynchronizationConfigState = SynchronizationConfigState(EngHubConfig()),
        loadedGitHubConfig: LoadedGitHubConfig = LoadedGitHubConfig(
            GitHubConfig(SECRET_PATH),
            GitHubSecret("token"),
        ),
        secretWriter: GitHubSecretWriter = SynchronizationSecretWriter(),
        saveGitHubAccess: suspend (Path, GitHubSecret) -> LoadedGitHubConfig = { path, secret ->
            secretWriter.save(path, secret)
            LoadedGitHubConfig(GitHubConfig(path.toString()), secret)
        },
        callbacks: GitHubSynchronizationCallbacks = GitHubSynchronizationCallbacks(),
    ) = EngHubSettingsViewModel(
        engHubConfig = configState.current,
        loadedGitHubConfig = loadedGitHubConfig,
        directoryPicker = NoOpSynchronizationDirectoryPicker,
        filePicker = NoOpSynchronizationFilePicker,
        persistence = EngHubSettingsPersistence(
            updateConfig = configState::update,
            gitHubSecretWriter = secretWriter,
            validateGitHubSecretPath = callbacks.validateSecretPath,
            saveGitHubAccess = saveGitHubAccess,
            onGitHubAccessCommitted = callbacks.onAccessCommitted,
            committedConfigUpdates = configState.updates,
        ),
        coroutineScope = backgroundScope,
    )

    private companion object {
        const val SECRET_PATH = "/secrets/github.json"
    }
}

private data class GitHubSynchronizationCallbacks(
    val validateSecretPath: (Path) -> Unit = {},
    val onAccessCommitted: (LoadedGitHubConfig) -> Unit = {},
)

private data class CollidingRepositoryFixture(
    val viewModel: EngHubSettingsViewModel,
    val configState: SynchronizationConfigState,
    val externalRepository: LocalRepositoryConfig,
)

private class SynchronizationConfigState(
    initial: EngHubConfig,
    private val yieldAfterEmission: Boolean = false,
) {
    private val mutable = MutableStateFlow(initial)

    var current: EngHubConfig
        get() = mutable.value
        set(value) {
            mutable.value = value
        }

    val updates: StateFlow<EngHubConfig> = mutable

    suspend fun update(transform: (EngHubConfig) -> EngHubConfig): EngHubConfig {
        val updated = transform(current)
        current = updated
        if (yieldAfterEmission) yield()
        return updated
    }
}

private class SynchronizationSecretWriter : GitHubSecretWriter {
    val savedSecrets = mutableListOf<Pair<Path, GitHubSecret>>()

    override suspend fun save(secretPath: Path, secret: GitHubSecret) {
        savedSecrets += secretPath to secret
    }
}

private object NoOpSynchronizationDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

private object NoOpSynchronizationFilePicker : FilePicker {
    override suspend fun pickFilePath(title: String): String? = null
}
