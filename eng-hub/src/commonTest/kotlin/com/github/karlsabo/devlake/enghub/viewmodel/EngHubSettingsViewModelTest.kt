package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriteException
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriteException
import com.github.karlsabo.github.config.GitHubSecretWriter
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubSettingsViewModelTest {
    @Test
    fun addingALocalRepositoryPersistsItWithNoSetupCommandsImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(
            EngHubConfig(localRepositories = listOf(LocalRepositoryConfig(path = "/workspace/api"))),
        )
        val viewModel = settingsViewModel(writer, configState)

        viewModel.localRepositorySettings.updateDraft("/workspace/web")
        viewModel.localRepositorySettings.add()
        runCurrent()

        val addedRepository = LocalRepositoryConfig(path = "/workspace/web")
        assertEquals("", viewModel.uiState.value.localRepositoryDraft)
        assertEquals(addedRepository, writer.savedConfigs.single().localRepositories.last())
        assertEquals(emptyList(), addedRepository.setupCommands)
    }

    @Test
    fun removingALocalRepositoryPersistsTheRemainingEntriesImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val retainedRepository = LocalRepositoryConfig(path = "/workspace/api")
        val removedRepository = LocalRepositoryConfig(
            path = "/workspace/old",
            setupCommands = listOf("direnv allow"),
        )
        val configState = MutableConfigState(
            EngHubConfig(localRepositories = listOf(retainedRepository, removedRepository)),
        )
        val viewModel = settingsViewModel(writer, configState)

        viewModel.localRepositorySettings.remove(1)
        runCurrent()

        assertEquals(listOf(retainedRepository.path), viewModel.uiState.value.localRepositories.map { it.path })
        assertEquals(listOf(retainedRepository), writer.savedConfigs.single().localRepositories)
    }

    @Test
    fun repositoryRemovalFindsExpectedPathWhenRepositoriesWereReordered() {
        val target = LocalRepositoryConfig(path = "/workspace/target")
        val retained = LocalRepositoryConfig(path = "/workspace/retained")
        val reorderedConfig = EngHubConfig(localRepositories = listOf(retained, target))

        val removed = reorderedConfig.removeLocalRepository(
            repositoryIndex = 0,
            expectedPath = target.path,
        )

        assertEquals(target, removed.repository)
        assertEquals(1, removed.index)
        assertEquals(listOf(retained), removed.config.localRepositories)
    }

    @Test
    fun undoingALocalRepositoryRemovalRestoresItsExactConfigImmediately() = runTest {
        val repositoryDirectory = Path(
            SystemTemporaryDirectory,
            "workspace-api-${Random.nextLong().toULong().toString(16)}",
        )
        val repositoryFile = Path(repositoryDirectory, "keep.txt")
        SystemFileSystem.createDirectories(repositoryDirectory)
        SystemFileSystem.sink(repositoryFile).buffered().use { it.writeString("keep") }
        try {
            val writer = RecordingConfigWriter()
            val repository = LocalRepositoryConfig(
                path = repositoryDirectory.toString(),
                setupCommands = listOf("cp .env.example .env", "direnv allow"),
            )
            val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
            val viewModel = settingsViewModel(writer, configState)

            viewModel.localRepositorySettings.remove(0)
            viewModel.localRepositorySettings.undoRemoval()
            runCurrent()

            assertEquals(emptyList(), writer.savedConfigs.first().localRepositories)
            assertEquals(listOf(repository), writer.savedConfigs.last().localRepositories)
            assertEquals(repository.path, viewModel.uiState.value.localRepositories.single().path)
            assertEquals(repository.setupCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
            assertEquals(null, viewModel.uiState.value.removedLocalRepositoryPath)
            assertTrue(SystemFileSystem.exists(repositoryFile))
        } finally {
            SystemFileSystem.delete(repositoryFile, mustExist = false)
            SystemFileSystem.delete(repositoryDirectory, mustExist = false)
        }
    }

    @Test
    fun localRepositoryRemovalUndoExpiresAfterItsShortWindow() = runTest {
        val repository = LocalRepositoryConfig(path = "/workspace/api")
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = settingsViewModel(RecordingConfigWriter(), configState)

        viewModel.localRepositorySettings.remove(0)
        advanceTimeBy((LOCAL_REPOSITORY_UNDO_DURATION_MS - 1).milliseconds)
        runCurrent()
        assertEquals(repository.path, viewModel.uiState.value.removedLocalRepositoryPath)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(null, viewModel.uiState.value.removedLocalRepositoryPath)
    }

    @Test
    fun blankLocalRepositoryPathRemainsInTheDraftWithAnError() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.localRepositorySettings.updateDraft("  ")
        viewModel.localRepositorySettings.add()
        runCurrent()

        assertEquals("  ", viewModel.uiState.value.localRepositoryDraft)
        assertEquals(LOCAL_REPOSITORY_BLANK_ERROR, viewModel.uiState.value.localRepositoryError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun normalizedDuplicateLocalRepositoryPathRemainsInTheDraftWithAnError() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(
            EngHubConfig(localRepositories = listOf(LocalRepositoryConfig(path = "/workspace/web"))),
        )
        val viewModel = settingsViewModel(writer, configState)

        viewModel.localRepositorySettings.updateDraft(" /workspace/web/ ")
        viewModel.localRepositorySettings.add()
        runCurrent()

        assertEquals(" /workspace/web/ ", viewModel.uiState.value.localRepositoryDraft)
        assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, viewModel.uiState.value.localRepositoryError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun addingASetupCommandBeforeAnExistingCommandPersistsTheirOrderImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val repository = LocalRepositoryConfig(
            path = "/workspace/api",
            setupCommands = listOf("direnv allow"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.setupCommandSettings.updateDraft(0, "cp .env.example .env")
        viewModel.setupCommandSettings.add(repositoryIndex = 0, insertionIndex = 0)
        runCurrent()

        val expectedCommands = listOf("cp .env.example .env", "direnv allow")
        assertEquals(expectedCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
        assertEquals(expectedCommands, writer.savedConfigs.single().localRepositories.single().setupCommands)
    }

    @Test
    fun removingASetupCommandPersistsTheRemainingCommandsImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val repository = LocalRepositoryConfig(
            path = "/workspace/api",
            setupCommands = listOf("cp .env.example .env", "direnv allow"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.setupCommandSettings.remove(repositoryIndex = 0, commandIndex = 0)
        runCurrent()

        val expectedCommands = listOf("direnv allow")
        assertEquals(expectedCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
        assertEquals(expectedCommands, writer.savedConfigs.single().localRepositories.single().setupCommands)
    }

    @Test
    fun editingASetupCommandPersistsItInPlaceAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val repository = LocalRepositoryConfig(
            path = "/workspace/api",
            setupCommands = listOf("direnv allow"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.setupCommandSettings.update(0, 0, "direnv allow .")

        assertEquals("direnv allow .", viewModel.uiState.value.localRepositories.single().setupCommands.single())
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(
            listOf("direnv allow ."),
            writer.savedConfigs.single().localRepositories.single().setupCommands,
        )
    }

    @Test
    fun blankSetupCommandEditRemainsVisibleWithoutPersisting() = runTest {
        val writer = RecordingConfigWriter()
        val repository = LocalRepositoryConfig(path = "/workspace/api", setupCommands = listOf("direnv allow"))
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.setupCommandSettings.update(0, 0, "  ")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        val repositoryState = viewModel.uiState.value.localRepositories.single()
        assertEquals("  ", repositoryState.setupCommands.single())
        assertEquals(SETUP_COMMAND_BLANK_ERROR, repositoryState.setupCommandEditErrors[0])
        assertTrue(writer.savedConfigs.isEmpty())
        assertEquals(listOf("direnv allow"), configState.current.localRepositories.single().setupCommands)
    }

    @Test
    fun blankSetupCommandRemainsInTheDraftWithAnError() = runTest {
        val writer = RecordingConfigWriter()
        val repository = LocalRepositoryConfig(path = "/workspace/api", setupCommands = listOf("direnv allow"))
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.setupCommandSettings.updateDraft(0, "  ")
        viewModel.setupCommandSettings.add(repositoryIndex = 0, insertionIndex = 0)
        runCurrent()

        val repositoryState = viewModel.uiState.value.localRepositories.single()
        assertEquals("  ", repositoryState.setupCommandDraft)
        assertEquals(SETUP_COMMAND_BLANK_ERROR, repositoryState.setupCommandError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun addingAnOrganizationIdPersistsImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(organizationIds = listOf("acme")))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.updateOrganizationIdDraft("widgets")
        viewModel.addOrganizationId()
        runCurrent()

        assertEquals(listOf("acme", "widgets"), viewModel.uiState.value.organizationIds)
        assertEquals("", viewModel.uiState.value.organizationIdDraft)
        assertEquals(listOf("acme", "widgets"), writer.savedConfigs.single().organizationIds)
    }

    @Test
    fun removingAnOrganizationIdPersistsTheRemainingIdsImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(organizationIds = listOf("acme", "example")))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.removeOrganizationId(1)
        runCurrent()

        assertEquals(listOf("acme"), viewModel.uiState.value.organizationIds)
        assertEquals(listOf("acme"), writer.savedConfigs.single().organizationIds)
    }

    @Test
    fun blankOrganizationIdRemainsInTheDraftWithAnError() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.updateOrganizationIdDraft("  ")
        viewModel.addOrganizationId()
        runCurrent()

        assertEquals("  ", viewModel.uiState.value.organizationIdDraft)
        assertEquals(ORGANIZATION_ID_BLANK_ERROR, viewModel.uiState.value.organizationIdError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun duplicateOrganizationIdRemainsInTheDraftWithAnError() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(organizationIds = listOf("acme")))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.updateOrganizationIdDraft("ACME")
        viewModel.addOrganizationId()
        runCurrent()

        assertEquals("ACME", viewModel.uiState.value.organizationIdDraft)
        assertEquals(ORGANIZATION_ID_DUPLICATE_ERROR, viewModel.uiState.value.organizationIdError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun choosingRepositoriesBaseDirectoryUpdatesTheFieldAndPersistsImmediately() = runTest {
        val writer = RecordingConfigWriter()
        val picker = RecordingSettingsDirectoryPicker("/workspace")
        val viewModel = settingsViewModel(
            writer,
            dependencies = SettingsViewModelDependencies(directoryPicker = picker),
        )

        viewModel.directorySettings.chooseRepositoriesBaseDir()
        runCurrent()

        assertEquals(listOf("Choose repositories base directory"), picker.titles)
        assertEquals("/workspace", viewModel.uiState.value.repositoriesBaseDir)
        assertEquals("/workspace", writer.savedConfigs.single().repositoriesBaseDir)
    }

    @Test
    fun choosingAConfiguredRepositoryPathPersistsItWithoutChangingSetupCommands() = runTest {
        val writer = RecordingConfigWriter()
        val repository = LocalRepositoryConfig(
            path = "/workspace/old",
            setupCommands = listOf("cp .env.example .env", "direnv allow"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = listOf(repository)))
        val picker = RecordingSettingsDirectoryPicker("/workspace/new")
        val viewModel = settingsViewModel(
            writer = writer,
            configState = configState,
            dependencies = SettingsViewModelDependencies(directoryPicker = picker),
        )

        viewModel.localRepositorySettings.choosePath(0)
        runCurrent()

        val updatedRepository = writer.savedConfigs.single().localRepositories.single()
        assertEquals(listOf("Choose local repository directory"), picker.titles)
        assertEquals("/workspace/new", updatedRepository.path)
        assertEquals(repository.setupCommands, updatedRepository.setupCommands)
        assertEquals("/workspace/new", viewModel.uiState.value.localRepositories.single().path)
        assertEquals(repository.setupCommands, viewModel.uiState.value.localRepositories.single().setupCommands)
    }

    @Test
    fun choosingADuplicateConfiguredRepositoryPathShowsAnErrorWithoutPersisting() = runTest {
        val writer = RecordingConfigWriter()
        val repositories = listOf(
            LocalRepositoryConfig(path = "/workspace/api", setupCommands = listOf("direnv allow")),
            LocalRepositoryConfig(path = "/workspace/web"),
        )
        val configState = MutableConfigState(EngHubConfig(localRepositories = repositories))
        val viewModel = settingsViewModel(
            writer = writer,
            configState = configState,
            dependencies = SettingsViewModelDependencies(
                directoryPicker = RecordingSettingsDirectoryPicker("/workspace/other/../web"),
            ),
        )

        viewModel.localRepositorySettings.choosePath(0)
        runCurrent()

        val repositoryState = viewModel.uiState.value.localRepositories.first()
        assertEquals("/workspace/api", repositoryState.path)
        assertEquals(LOCAL_REPOSITORY_DUPLICATE_ERROR, repositoryState.pathError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun cancellingRepositoriesBaseDirectoryPickerDoesNotChangeConfiguration() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(
            writer,
            dependencies = SettingsViewModelDependencies(
                directoryPicker = RecordingSettingsDirectoryPicker(null),
            ),
        )

        viewModel.directorySettings.chooseRepositoriesBaseDir()
        runCurrent()

        assertEquals("", viewModel.uiState.value.repositoriesBaseDir)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun typingRepositoriesBaseDirectoryPersistsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.directorySettings.updateRepositoriesBaseDir("/workspace")

        assertEquals("/workspace", viewModel.uiState.value.repositoriesBaseDir)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals("/workspace", writer.savedConfigs.single().repositoriesBaseDir)
    }

    @Test
    fun creatingGitHubAccessPersistsTheSecretAndConfigBeforeEnablingPanes() = runTest {
        val savedAccess = mutableListOf<Pair<Path, GitHubSecret>>()
        val committedAccess = mutableListOf<LoadedGitHubConfig>()
        val viewModel = settingsViewModel(
            writer = RecordingConfigWriter(),
            dependencies = SettingsViewModelDependencies(
                loadedGitHubConfig = LoadedGitHubConfig(
                    GitHubConfig(tokenPath = ""),
                    GitHubSecret(githubToken = ""),
                ),
                saveGitHubAccess = { path, secret ->
                    savedAccess += path to secret
                    LoadedGitHubConfig(GitHubConfig(path.toString()), secret)
                },
                onGitHubAccessCommitted = committedAccess::add,
            ),
        )

        viewModel.gitHubTokenSettings.updateSecretPath("/secrets/new-github.json")
        viewModel.gitHubTokenSettings.updateToken("github_pat_new")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        val expected = LoadedGitHubConfig(
            GitHubConfig("/secrets/new-github.json"),
            GitHubSecret("github_pat_new"),
        )
        assertEquals(listOf(Path("/secrets/new-github.json") to expected.secret), savedAccess)
        assertEquals(listOf(expected), committedAccess)
        assertTrue(viewModel.uiState.value.gitHubAccessReady)
    }

    @Test
    fun choosingANewGitHubSecretPathCopiesTheLoadedTokenImmediately() = runTest {
        val savedAccess = mutableListOf<Pair<Path, GitHubSecret>>()
        val viewModel = settingsViewModel(
            writer = RecordingConfigWriter(),
            dependencies = SettingsViewModelDependencies(
                filePicker = RecordingSettingsFilePicker("/secrets/new-github.json"),
                saveGitHubAccess = { path, secret ->
                    savedAccess += path to secret
                    LoadedGitHubConfig(GitHubConfig(path.toString()), secret)
                },
            ),
        )

        viewModel.gitHubTokenSettings.chooseSecretPath()
        runCurrent()

        assertEquals(
            listOf(Path("/secrets/new-github.json") to GitHubSecret("token")),
            savedAccess,
        )
        assertEquals("/secrets/new-github.json", viewModel.uiState.value.gitHubTokenPath)
    }

    @Test
    fun replacingGitHubTokenPersistsOnlyTheReferencedSecretAfter750Milliseconds() = runTest {
        val configWriter = RecordingConfigWriter()
        val secretWriter = RecordingGitHubSecretWriter()
        val viewModel = settingsViewModel(
            configWriter,
            dependencies = SettingsViewModelDependencies(gitHubSecretWriter = secretWriter),
        )

        viewModel.gitHubTokenSettings.updateToken("github_pat_new")

        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(secretWriter.savedSecrets.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(
            listOf(Path("/secrets/github.json") to GitHubSecret("github_pat_new")),
            secretWriter.savedSecrets,
        )
        assertTrue(configWriter.savedConfigs.isEmpty())
        assertEquals("••••••••", viewModel.uiState.value.gitHubToken.maskedValue)
    }

    @Test
    fun secretPermissionFailureKeepsTheTokenDraftAndShowsAnActionableError() = runTest {
        val replacement = "github_pat_new"
        val failingWriter = GitHubSecretWriter { _, _ ->
            throw GitHubSecretWriteException("Could not set permissions for $replacement")
        }
        val viewModel = settingsViewModel(
            RecordingConfigWriter(),
            dependencies = SettingsViewModelDependencies(gitHubSecretWriter = failingWriter),
        )

        viewModel.gitHubTokenSettings.updateToken(replacement)
        advanceTimeBy(750.milliseconds)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("token", state.gitHubToken.value)
        assertEquals(GITHUB_SECRET_SAVE_ERROR, state.gitHubTokenError)
        assertFalse(state.gitHubTokenError.orEmpty().contains(replacement))
        assertTrue(state.gitHubAccessReady)
    }

    @Test
    fun unchangedLoadedGitHubTokenIsNotRewritten() = runTest {
        val secretWriter = RecordingGitHubSecretWriter()
        val viewModel = settingsViewModel(
            RecordingConfigWriter(),
            dependencies = SettingsViewModelDependencies(gitHubSecretWriter = secretWriter),
        )
        viewModel.gitHubTokenSettings.updateToken("token")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertTrue(secretWriter.savedSecrets.isEmpty())
    }

    @Test
    fun changingGitHubAuthorUpdatesTheDraftAndPersistsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")

        assertEquals("hubot", viewModel.uiState.value.gitHubAuthor)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf("hubot"), writer.savedConfigs.map(EngHubConfig::gitHubAuthor))
    }

    @Test
    fun navigationFlushesPendingAuthorBeforeSelectingThePaneWithoutAStaleDebounceWrite() = runTest {
        val events = mutableListOf<String>()
        val writer = RecordingConfigWriter { config -> events += "persisted ${config.gitHubAuthor}" }
        val viewModel = settingsViewModel(writer)

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
        val navigation = launchAfterSettingsFlush(viewModel) { events += "navigated" }
        navigation.join()

        assertEquals(listOf("persisted hubot", "navigated"), events)
        advanceTimeBy(750.milliseconds)
        runCurrent()
        assertEquals(listOf("hubot"), writer.savedConfigs.map(EngHubConfig::gitHubAuthor))
    }

    @Test
    fun cancelledSettingsFlushDoesNotRunItsStaleNavigationAction() = runTest {
        var saveCalls = 0
        val secretWriter = GitHubSecretWriter { _, _ ->
            saveCalls += 1
            if (saveCalls == 1) awaitCancellation()
        }
        val viewModel = settingsViewModel(
            RecordingConfigWriter(),
            dependencies = SettingsViewModelDependencies(gitHubSecretWriter = secretWriter),
        )
        val navigations = mutableListOf<String>()
        viewModel.gitHubTokenSettings.updateToken("replacement")

        val staleNavigation = launchAfterSettingsFlush(viewModel) { navigations += "stale" }
        runCurrent()
        staleNavigation.cancelAndJoin()
        launchAfterSettingsFlush(viewModel) { navigations += "latest" }.join()

        assertEquals(listOf("latest"), navigations)
        assertEquals(2, saveCalls)
    }

    @Test
    fun navigationDoesNotPersistAnInvalidPendingPollingInterval() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)
        var navigated = false

        viewModel.generalTextSettings.updatePollIntervalSeconds("0")
        val navigation = launchAfterSettingsFlush(viewModel) { navigated = true }
        navigation.join()

        assertTrue(navigated)
        assertEquals("0", viewModel.uiState.value.pollIntervalSeconds)
        assertEquals(POLL_INTERVAL_ERROR, viewModel.uiState.value.pollIntervalError)
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun autoSaveFailureKeepsTheDraftVisibleAndRuntimeOnThePersistedConfig() = runTest {
        val persistedConfig = EngHubConfig(gitHubAuthor = "octocat")
        val configState = MutableConfigState(persistedConfig)
        val writer = FailingConfigWriter()
        val viewModel = settingsViewModel(writer, configState)

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals("hubot", viewModel.uiState.value.gitHubAuthor)
        assertEquals(SETTINGS_PERSISTENCE_ERROR, viewModel.uiState.value.persistenceError)
        assertEquals(persistedConfig, configState.current)
        assertEquals("hubot", writer.attemptedConfigs.single().gitHubAuthor)
    }

    @Test
    fun changingPollingIntervalCommitsMillisecondsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.generalTextSettings.updatePollIntervalSeconds("300")

        assertEquals("300", viewModel.uiState.value.pollIntervalSeconds)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(300_000L), writer.savedConfigs.map(EngHubConfig::pollIntervalMs))
    }

    @Test
    fun changingWorktreePollingIntervalCommitsMillisecondsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(worktreePollIntervalMs = 120_000))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.generalTextSettings.updateWorktreePollIntervalSeconds("60")

        assertEquals("60", viewModel.uiState.value.worktreePollIntervalSeconds)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(60_000L), writer.savedConfigs.map(EngHubConfig::worktreePollIntervalMs))
    }

    @Test
    fun changingSetupShellPersistsAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(setupShell = "/bin/zsh"))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.generalTextSettings.updateSetupShell("/bin/bash")

        assertEquals("/bin/bash", viewModel.uiState.value.setupShell)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals("/bin/bash", writer.savedConfigs.single().setupShell)
        assertEquals("/bin/bash", configState.current.setupShell)
    }

    @Test
    fun invalidWorktreePollingIntervalRemainsVisibleWithoutUpdatingConfiguration() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(worktreePollIntervalMs = 120_000))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.generalTextSettings.updateWorktreePollIntervalSeconds("1.5")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals("1.5", viewModel.uiState.value.worktreePollIntervalSeconds)
        assertEquals(POLL_INTERVAL_ERROR, viewModel.uiState.value.worktreePollIntervalError)
        assertTrue(writer.savedConfigs.isEmpty())
        assertEquals(120_000, configState.current.worktreePollIntervalMs)
    }

    @Test
    fun zeroPollingIntervalRemainsVisibleWithAnErrorWithoutUpdatingConfiguration() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(EngHubConfig(pollIntervalMs = 600_000))
        val viewModel = settingsViewModel(writer, configState)

        viewModel.generalTextSettings.updatePollIntervalSeconds("0")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals("0", viewModel.uiState.value.pollIntervalSeconds)
        assertEquals(POLL_INTERVAL_ERROR, viewModel.uiState.value.pollIntervalError)
        assertTrue(writer.savedConfigs.isEmpty())
        assertEquals(600_000, configState.current.pollIntervalMs)
    }

    @Test
    fun aNewerDraftCannotBeOverwrittenByAnOlderDebounce() = runTest {
        val writer = RecordingConfigWriter()
        val viewModel = settingsViewModel(writer)

        viewModel.generalTextSettings.updateGitHubAuthor("robot")
        advanceTimeBy(500.milliseconds)
        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
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

        viewModel.generalTextSettings.updateGitHubAuthor("hubot")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(listOf(repository), writer.savedConfigs.single().localRepositories)
    }
}

internal fun kotlinx.coroutines.test.TestScope.settingsViewModel(
    writer: EngHubConfigWriter,
    configState: MutableConfigState = MutableConfigState(EngHubConfig(gitHubAuthor = "octocat")),
    dependencies: SettingsViewModelDependencies = SettingsViewModelDependencies(),
    repositoryPathOsFamily: OsFamily = osFamily(),
) = EngHubSettingsViewModel(
    engHubConfig = configState.current,
    loadedGitHubConfig = dependencies.loadedGitHubConfig,
    directoryPicker = dependencies.directoryPicker,
    filePicker = dependencies.filePicker,
    persistence = EngHubSettingsPersistence(
        gitHubSecretWriter = dependencies.gitHubSecretWriter,
        saveGitHubAccess = dependencies.saveGitHubAccess,
        onGitHubAccessCommitted = dependencies.onGitHubAccessCommitted,
        committedConfigUpdates = configState.updates,
        updateConfig = { transform ->
            transform(configState.current).also { updatedConfig ->
                writer.save(updatedConfig)
                configState.current = updatedConfig
            }
        },
    ).also { persistence -> persistence.repositoryPathOsFamily = repositoryPathOsFamily },
    coroutineScope = backgroundScope,
)

internal data class SettingsViewModelDependencies(
    val directoryPicker: DirectoryPicker = RecordingSettingsDirectoryPicker(null),
    val filePicker: FilePicker = RecordingSettingsFilePicker(null),
    val loadedGitHubConfig: LoadedGitHubConfig = LoadedGitHubConfig(
        GitHubConfig(tokenPath = "/secrets/github.json"),
        GitHubSecret(githubToken = "token"),
    ),
    val gitHubSecretWriter: GitHubSecretWriter = RecordingGitHubSecretWriter(),
    val saveGitHubAccess: suspend (Path, GitHubSecret) -> LoadedGitHubConfig = { path, secret ->
        gitHubSecretWriter.save(path, secret)
        LoadedGitHubConfig(GitHubConfig(path.toString()), secret)
    },
    val onGitHubAccessCommitted: (LoadedGitHubConfig) -> Unit = {},
)

private class RecordingSettingsDirectoryPicker(
    private val selectedPath: String?,
) : DirectoryPicker {
    val titles = mutableListOf<String>()

    override suspend fun pickDirectory(title: String): String? {
        titles += title
        return selectedPath
    }
}

private class RecordingSettingsFilePicker(
    private val selectedPath: String?,
) : FilePicker {
    override suspend fun pickFilePath(title: String): String? = selectedPath
}

private class RecordingGitHubSecretWriter : GitHubSecretWriter {
    val savedSecrets = mutableListOf<Pair<Path, GitHubSecret>>()

    override suspend fun save(secretPath: Path, secret: GitHubSecret) {
        savedSecrets += secretPath to secret
    }
}

internal class MutableConfigState(
    initial: EngHubConfig,
) {
    private val mutable = MutableStateFlow(initial)

    var current: EngHubConfig
        get() = mutable.value
        set(value) {
            mutable.value = value
        }

    val updates: StateFlow<EngHubConfig> = mutable
}

internal class RecordingConfigWriter(
    private val onSave: (EngHubConfig) -> Unit = {},
) : EngHubConfigWriter {
    val savedConfigs = mutableListOf<EngHubConfig>()

    override fun save(config: EngHubConfig) {
        savedConfigs += config
        onSave(config)
    }
}

private class FailingConfigWriter : EngHubConfigWriter {
    val attemptedConfigs = mutableListOf<EngHubConfig>()

    override fun save(config: EngHubConfig) {
        attemptedConfigs += config
        throw EngHubConfigWriteException("Storage failed", IllegalStateException("read-only filesystem"))
    }
}
