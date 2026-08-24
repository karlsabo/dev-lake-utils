package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriteException
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriter
import com.github.karlsabo.github.config.LoadedGitHubConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubSettingsPersistenceRegressionTest {
    @Test
    fun failedAuthorSaveRetriesBeforeALaterOrganizationWriteAndClearsOnlyAfterSuccess() = runTest {
        val state = RegressionConfigState(EngHubConfig(gitHubAuthor = "octocat", organizationIds = listOf("acme")))
        val writer = FailsOnceRegressionConfigWriter()
        val viewModel = regressionViewModel(writer, state)

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        assertEquals(SETTINGS_PERSISTENCE_ERROR, viewModel.uiState.value.persistenceError)
        assertEquals("octocat", viewModel.uiState.value.committedConfig.gitHubAuthor)

        viewModel.updateOrganizationIdDraft("widgets")
        viewModel.addOrganizationId()
        runCurrent()

        assertEquals("hubot", state.current.gitHubAuthor)
        assertEquals(listOf("acme", "widgets"), state.current.organizationIds)
        assertEquals("hubot", viewModel.uiState.value.committedConfig.gitHubAuthor)
        assertEquals(null, viewModel.uiState.value.persistenceError)
    }

    @Test
    fun verificationFailureKeepsRuntimeConfigAndRetryableDraftWithActionableError() = runTest {
        val state = RegressionConfigState(EngHubConfig(gitHubAuthor = "octocat"))
        val viewModel = regressionViewModel(VerificationFailureConfigWriter(), state)

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        viewModel.flushPendingEdits()

        assertEquals("octocat", state.current.gitHubAuthor)
        assertEquals("octocat", viewModel.uiState.value.committedConfig.gitHubAuthor)
        assertEquals("hubot", viewModel.uiState.value.gitHubAuthor)
        assertEquals(SETTINGS_PERSISTENCE_ERROR, viewModel.uiState.value.persistenceError)
    }

    @Test
    fun lifecycleFlushRetriesAFailedAuthorDraft() = runTest {
        val state = RegressionConfigState(EngHubConfig(gitHubAuthor = "octocat"))
        val writer = FailsOnceRegressionConfigWriter()
        val viewModel = regressionViewModel(writer, state)

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        viewModel.flushPendingEdits()

        assertEquals("hubot", state.current.gitHubAuthor)
        assertEquals(null, viewModel.uiState.value.persistenceError)
        assertTrue(writer.attemptedConfigs.size >= 2)
    }

    @Test
    fun overlappingTokenWritesFinishInDraftOrder() = runTest {
        val writer = BlockingFirstGitHubSecretWriter()
        val committedTokens = mutableListOf<String>()
        val viewModel = regressionViewModel(
            writer = RecordingRegressionConfigWriter(),
            secretWriter = writer,
            onGitHubAccessCommitted = { loaded -> committedTokens += loaded.secret.githubToken },
        )

        viewModel.gitHubTokenSettings.updateToken("token-a")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        writer.firstWriteStarted.await()

        viewModel.gitHubTokenSettings.updateToken("token-b")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        assertTrue(writer.savedTokens.isEmpty())

        writer.releaseFirstWrite.complete(Unit)
        runCurrent()

        assertEquals(listOf("token-a", "token-b"), writer.savedTokens)
        assertEquals(listOf("token-a", "token-b"), committedTokens)
        assertEquals("token-b", viewModel.uiState.value.gitHubToken.value)
    }

    @Test
    fun repeatedCommandEditPersistsLatestValueAfterAnEarlierWriteHasStarted() = runTest {
        val repository = LocalRepositoryConfig("/workspace/api", listOf("original"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val blocker = BlockingRegressionConfigUpdate(state)
        state.configUpdate = blocker::update
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.update(0, 0, "first")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        blocker.firstWriteStarted.await()
        viewModel.setupCommandSettings.update(0, 0, "second")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        blocker.releaseFirstWrite.complete(Unit)
        viewModel.flushPendingEdits()

        assertEquals(
            listOf(listOf("first"), listOf("second")),
            blocker.savedConfigs.map { it.singleRepositoryCommands() },
        )
        assertEquals(listOf("second"), state.current.singleRepositoryCommands())
        assertEquals(listOf("second"), viewModel.uiState.value.localRepositories.single().setupCommands)
    }

    @Test
    fun repositoryReeditPersistsLatestPathAfterAnEarlierWriteHasStarted() = runTest {
        val repository = LocalRepositoryConfig("/workspace/old", listOf("prepare"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val blocker = BlockingRegressionConfigUpdate(state)
        state.configUpdate = blocker::update
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/first")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        blocker.firstWriteStarted.await()
        viewModel.localRepositorySettings.updatePath(0, "/workspace/second")

        blocker.releaseFirstWrite.complete(Unit)
        viewModel.flushPendingEdits()

        assertEquals(
            listOf("/workspace/first", "/workspace/second"),
            blocker.savedConfigs.map { it.localRepositories.single().path },
        )
        assertEquals("/workspace/second", state.current.localRepositories.single().path)
        assertEquals("/workspace/second", viewModel.uiState.value.localRepositories.single().path)
        assertEquals(repository.setupCommands, state.current.singleRepositoryCommands())
    }

    @Test
    fun repositoryRemovalFollowsAnEarlierInFlightPathWrite() = runTest {
        val repository = LocalRepositoryConfig("/workspace/old", listOf("prepare"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val blocker = BlockingRegressionConfigUpdate(state)
        state.configUpdate = blocker::update
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/first")
        advanceTimeBy(750.milliseconds)
        runCurrent()
        blocker.firstWriteStarted.await()
        viewModel.localRepositorySettings.remove(0)

        blocker.releaseFirstWrite.complete(Unit)
        viewModel.flushPendingEdits()

        assertEquals(listOf(1, 0), blocker.savedConfigs.map { it.localRepositories.size })
        assertTrue(state.current.localRepositories.isEmpty())
        assertTrue(viewModel.uiState.value.localRepositories.isEmpty())
    }

    @Test
    fun commandEditIsRebasedWhenACommandIsInsertedBeforeIt() = runTest {
        val repository = LocalRepositoryConfig("/workspace/api", listOf("command-b"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.update(0, 0, "command-b-edited")
        viewModel.setupCommandSettings.updateDraft(0, "command-c")
        viewModel.setupCommandSettings.add(0, 0)
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(listOf("command-c", "command-b-edited"), state.current.singleRepositoryCommands())
    }

    @Test
    fun commandEditIsRebasedWhenAnEarlierRepositoryIsRemoved() = runTest {
        val repositories = listOf(
            LocalRepositoryConfig("/workspace/first"),
            LocalRepositoryConfig("/workspace/second", listOf("prepare")),
        )
        val state = RegressionConfigState(EngHubConfig(localRepositories = repositories))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.update(1, 0, "prepare edited")
        viewModel.localRepositorySettings.remove(0)
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals("/workspace/second", state.current.localRepositories.single().path)
        assertEquals(listOf("prepare edited"), state.current.singleRepositoryCommands())
    }

    @Test
    fun rapidOrganizationAddThenRemovePersistsInInvocationOrderOnReverseDispatcher() = runTest {
        val dispatcher = ReverseQueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val state = RegressionConfigState(EngHubConfig(organizationIds = listOf("acme")))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state, coroutineScope = scope)

        viewModel.updateOrganizationIdDraft("widgets")
        viewModel.addOrganizationId()
        viewModel.removeOrganizationId(1)
        val flush = async { viewModel.flushPendingEdits() }
        runCurrent()

        assertFalse(flush.isCompleted)
        dispatcher.runUntilIdle()
        flush.await()

        assertEquals(listOf("acme"), state.current.organizationIds)
        assertEquals(listOf("acme"), viewModel.uiState.value.organizationIds)
    }

    @Test
    fun rapidRepositoryAddThenRemovePersistsInInvocationOrderOnReverseDispatcher() = runTest {
        val dispatcher = ReverseQueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val state = RegressionConfigState(EngHubConfig())
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state, coroutineScope = scope)

        viewModel.localRepositorySettings.updateDraft("/workspace/api")
        viewModel.localRepositorySettings.add()
        viewModel.localRepositorySettings.remove(0)
        val flush = async { viewModel.flushPendingEdits() }
        runCurrent()

        assertFalse(flush.isCompleted)
        dispatcher.runUntilIdle()
        flush.await()

        assertTrue(state.current.localRepositories.isEmpty())
        assertTrue(viewModel.uiState.value.localRepositories.isEmpty())
    }

    @Test
    fun navigationWaitsForAnImmediateOrganizationWrite() = runTest {
        val events = mutableListOf<String>()
        val state = RegressionConfigState(EngHubConfig(organizationIds = listOf("acme")))
        val writer = RecordingRegressionConfigWriter { config -> events += "persisted ${config.organizationIds}" }
        val viewModel = regressionViewModel(writer, state)

        viewModel.updateOrganizationIdDraft("widgets")
        viewModel.addOrganizationId()
        launchAfterSettingsFlush(viewModel) { events += "navigated" }.join()

        assertEquals(listOf("persisted [acme, widgets]", "navigated"), events)
    }

    @Test
    fun repositoryUndoRestoresAnImmediatelyAddedCommandInPersistenceOrder() = runTest {
        val repository = LocalRepositoryConfig("/workspace/api", listOf("prepare"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.updateDraft(0, "open")
        viewModel.setupCommandSettings.add(0, 1)
        viewModel.localRepositorySettings.remove(0)
        viewModel.localRepositorySettings.undoRemoval()
        viewModel.flushPendingEdits()

        val expectedCommands = listOf("prepare", "open")
        assertEquals(expectedCommands, state.current.singleRepositoryCommands())
        assertEquals(expectedCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
    }

    @Test
    fun repositoryUndoDoesNotResurrectAnImmediatelyRemovedCommand() = runTest {
        val repository = LocalRepositoryConfig("/workspace/api", listOf("prepare", "open"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.remove(0, 0)
        viewModel.localRepositorySettings.remove(0)
        viewModel.localRepositorySettings.undoRemoval()
        viewModel.flushPendingEdits()

        assertEquals(listOf("open"), state.current.singleRepositoryCommands())
        assertEquals(listOf("open"), viewModel.uiState.value.localRepositories.single().setupCommands)
    }

    @Test
    fun repositoryUndoWaitsForInFlightCommandPersistenceAndRestoresItsResult() = runTest {
        val repository = LocalRepositoryConfig("/workspace/api", listOf("prepare"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val blocker = BlockingRegressionConfigUpdate(state)
        state.configUpdate = blocker::update
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.updateDraft(0, "open")
        viewModel.setupCommandSettings.add(0, 1)
        runCurrent()
        blocker.firstWriteStarted.await()

        viewModel.localRepositorySettings.remove(0)
        viewModel.localRepositorySettings.undoRemoval()
        blocker.releaseFirstWrite.complete(Unit)
        viewModel.flushPendingEdits()

        val expectedCommands = listOf("prepare", "open")
        assertEquals(
            listOf(expectedCommands, emptyList(), expectedCommands),
            blocker.savedConfigs.map { config ->
                config.localRepositories.singleOrNull()?.setupCommands ?: emptyList()
            },
        )
        assertEquals(expectedCommands, state.current.singleRepositoryCommands())
        assertEquals(expectedCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
    }

    @Test
    fun navigationWaitsForImmediateRepositoryRemovalAndUndoWrites() = runTest {
        val events = mutableListOf<String>()
        val repository = LocalRepositoryConfig("/workspace/api", listOf("prepare"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val writer = RecordingRegressionConfigWriter { config ->
            events += "persisted ${config.localRepositories.size}"
        }
        val viewModel = regressionViewModel(writer, state)

        viewModel.localRepositorySettings.remove(0)
        viewModel.localRepositorySettings.undoRemoval()
        launchAfterSettingsFlush(viewModel) { events += "closed" }.join()

        assertEquals(listOf("persisted 0", "persisted 1", "closed"), events)
        assertEquals(repository, state.current.localRepositories.single())
    }

    @Test
    fun typingAnExistingRepositoryPathValidatesAndPersistsWithoutLosingCommands() = runTest {
        val repository = LocalRepositoryConfig("/workspace/old", listOf("prepare", "open"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val writer = RecordingRegressionConfigWriter()
        val viewModel = regressionViewModel(writer, state)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/new")
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals("/workspace/new", state.current.localRepositories.single().path)
        assertEquals(repository.setupCommands, state.current.singleRepositoryCommands())
    }

    @Test
    fun commandAddCommitsPendingRepositoryPathBeforeApplyingTheCommand() = runTest {
        val repository = LocalRepositoryConfig("/workspace/old", listOf("prepare"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/new")
        viewModel.setupCommandSettings.updateDraft(0, "open")
        viewModel.setupCommandSettings.add(0, 1)
        viewModel.flushPendingEdits()

        assertEquals("/workspace/new", state.current.localRepositories.single().path)
        assertEquals(listOf("prepare", "open"), state.current.singleRepositoryCommands())
    }

    @Test
    fun commandRemovalCommitsPendingRepositoryPathBeforeRemovingTheCommand() = runTest {
        val repository = LocalRepositoryConfig("/workspace/old", listOf("prepare", "open"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.localRepositorySettings.updatePath(0, "/workspace/new")
        viewModel.setupCommandSettings.remove(0, 0)
        viewModel.flushPendingEdits()

        assertEquals("/workspace/new", state.current.localRepositories.single().path)
        assertEquals(listOf("open"), state.current.singleRepositoryCommands())
    }

    @Test
    fun repositoryRemovalUndoRestoresPersistedCommandsInsteadOfInvalidDrafts() = runTest {
        val repository = LocalRepositoryConfig("/workspace/api", listOf("prepare", "open"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = regressionViewModel(RecordingRegressionConfigWriter(), state)

        viewModel.setupCommandSettings.update(0, 0, "")
        viewModel.localRepositorySettings.remove(0)
        viewModel.localRepositorySettings.undoRemoval()
        viewModel.flushPendingEdits()

        assertEquals(listOf(repository), state.current.localRepositories)
        assertEquals(repository.setupCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
    }

    @Test
    fun duplicateTypedRepositoryPathRemainsAnUnpersistedDraft() = runTest {
        val repositories = listOf(LocalRepositoryConfig("/workspace/api"), LocalRepositoryConfig("/workspace/web"))
        val state = RegressionConfigState(EngHubConfig(localRepositories = repositories))
        val writer = RecordingRegressionConfigWriter()
        val viewModel = regressionViewModel(writer, state)

        viewModel.localRepositorySettings.updatePath(0, " /workspace/web/ ")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(" /workspace/web/ ", viewModel.uiState.value.localRepositories.first().path)
        assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, viewModel.uiState.value.localRepositories.first().pathError)
        assertEquals(repositories, state.current.localRepositories)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    private fun TestScope.regressionViewModel(
        writer: EngHubConfigWriter,
        state: RegressionConfigState = RegressionConfigState(EngHubConfig(gitHubAuthor = "octocat")),
        secretWriter: GitHubSecretWriter = RecordingRegressionSecretWriter(),
        onGitHubAccessCommitted: (LoadedGitHubConfig) -> Unit = {},
        coroutineScope: CoroutineScope = this,
    ) = EngHubSettingsViewModel(
        engHubConfig = state.current,
        loadedGitHubConfig = LoadedGitHubConfig(
            GitHubConfig("/secrets/github.json"),
            GitHubSecret("token"),
        ),
        directoryPicker = NoOpRegressionDirectoryPicker,
        filePicker = NoOpRegressionFilePicker,
        persistence = EngHubSettingsPersistence(
            updateConfig = { transform -> state.update(transform, writer) },
            gitHubSecretWriter = secretWriter,
            onGitHubAccessCommitted = onGitHubAccessCommitted,
        ),
        coroutineScope = coroutineScope,
    )
}

private fun EngHubConfig.singleRepositoryCommands(): List<String> = localRepositories.single().setupCommands

private data class RegressionConfigState(
    var current: EngHubConfig,
    var configUpdate: (suspend ((EngHubConfig) -> EngHubConfig) -> EngHubConfig)? = null,
) {
    suspend fun update(
        transform: (EngHubConfig) -> EngHubConfig,
        writer: EngHubConfigWriter,
    ): EngHubConfig = configUpdate?.invoke(transform) ?: transform(current).also { updated ->
        writer.save(updated)
        current = updated
    }
}

private class BlockingRegressionConfigUpdate(
    private val state: RegressionConfigState,
) {
    val firstWriteStarted = CompletableDeferred<Unit>()
    val releaseFirstWrite = CompletableDeferred<Unit>()
    val savedConfigs = mutableListOf<EngHubConfig>()
    private var shouldBlock = true

    suspend fun update(transform: (EngHubConfig) -> EngHubConfig): EngHubConfig {
        val updated = transform(state.current)
        if (shouldBlock) {
            shouldBlock = false
            firstWriteStarted.complete(Unit)
            withContext(NonCancellable) {
                releaseFirstWrite.await()
                savedConfigs += updated
                state.current = updated
            }
        } else {
            savedConfigs += updated
            state.current = updated
        }
        return updated
    }
}

private class RecordingRegressionConfigWriter(
    private val onSave: (EngHubConfig) -> Unit = {},
) : EngHubConfigWriter {
    val savedConfigs = mutableListOf<EngHubConfig>()

    override fun save(config: EngHubConfig) {
        savedConfigs += config
        onSave(config)
    }
}

private class FailsOnceRegressionConfigWriter : EngHubConfigWriter {
    val attemptedConfigs = mutableListOf<EngHubConfig>()

    override fun save(config: EngHubConfig) {
        attemptedConfigs += config
        if (attemptedConfigs.size == 1) {
            throw EngHubConfigWriteException("Storage failed", IllegalStateException("temporary failure"))
        }
    }
}

private class VerificationFailureConfigWriter : EngHubConfigWriter {
    override fun save(config: EngHubConfig): Unit = throw EngHubConfigWriteException(
        "Could not verify pending Eng Hub configuration",
        IllegalStateException("Verification failed"),
    )
}

private class BlockingFirstGitHubSecretWriter : GitHubSecretWriter {
    val firstWriteStarted = CompletableDeferred<Unit>()
    val releaseFirstWrite = CompletableDeferred<Unit>()
    val savedTokens = mutableListOf<String>()

    override suspend fun save(secretPath: Path, secret: GitHubSecret) {
        if (secret.githubToken == "token-a") {
            firstWriteStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirstWrite.await() }
        }
        savedTokens += secret.githubToken
    }
}

private class ReverseQueuedDispatcher : CoroutineDispatcher() {
    private val tasks = mutableListOf<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks += block
    }

    fun runUntilIdle() {
        while (tasks.isNotEmpty()) tasks.removeLast().run()
    }
}

private class RecordingRegressionSecretWriter : GitHubSecretWriter {
    override suspend fun save(secretPath: Path, secret: GitHubSecret) = Unit
}

private object NoOpRegressionDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

private object NoOpRegressionFilePicker : FilePicker {
    override suspend fun pickFilePath(title: String): String? = null
}
