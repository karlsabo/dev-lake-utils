package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.state.MASKED_TOKEN
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriter
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import com.github.karlsabo.gitlab.config.GitLabSecret
import com.github.karlsabo.gitlab.config.LoadedGitLabConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubGitLabSettingsViewModelTest {
    @Test
    fun savesInstanceUrlAndSecretSeparatelyAndMasksTheToken() = runTest {
        val persistence = RecordingGitLabPersistence()
        val currentConfig = MutableStateFlow(EngHubConfig())
        val viewModel = gitLabSettingsViewModel(currentConfig, persistence)

        viewModel.gitLabSettings.updateInstanceUrl("https://gitlab.internal.example/")
        viewModel.gitLabSettings.updateSecretPath("/secrets/gitlab.json")
        viewModel.gitLabSettings.updateToken("glpat-synthetic")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("https://gitlab.internal.example", state.gitLabInstanceUrl)
        assertEquals("/secrets/gitlab.json", state.gitLabTokenPath)
        assertEquals(MASKED_TOKEN, state.gitLabToken.maskedValue)
        assertEquals("glpat-synthetic", state.gitLabToken.fieldValue)
        assertTrue(state.gitLabAccessReady)
        assertEquals(listOf(Path("/secrets/gitlab.json") to GitLabSecret("glpat-synthetic")), persistence.secrets)
        assertEquals("https://gitlab.internal.example", currentConfig.value.gitLabInstanceUrl)
        assertEquals("/secrets/gitlab.json", currentConfig.value.gitLabTokenPath)
        assertEquals(
            GitLabApiRestConfig("https://gitlab.internal.example", "glpat-synthetic"),
            persistence.committedAccesses.last(),
        )
        assertFalse("glpat-synthetic" in state.toString())
    }

    @Test
    fun anUnencryptedInstanceUrlIsRejectedWithoutPersistingAnything() = runTest {
        val persistence = RecordingGitLabPersistence()
        val currentConfig = MutableStateFlow(EngHubConfig())
        val viewModel = gitLabSettingsViewModel(currentConfig, persistence)

        viewModel.gitLabSettings.updateInstanceUrl("http://gitlab.internal.example")
        viewModel.gitLabSettings.updateToken("glpat-synthetic")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(GITLAB_INSTANCE_URL_ERROR, viewModel.uiState.value.gitLabInstanceUrlError)
        assertTrue(persistence.secrets.isEmpty())
        assertTrue(persistence.configUpdates.isEmpty())
        assertFalse(viewModel.uiState.value.gitLabAccessReady)
    }

    @Test
    fun aTokenWithoutASecretPathReportsThePathError() = runTest {
        val persistence = RecordingGitLabPersistence()
        val viewModel = gitLabSettingsViewModel(MutableStateFlow(EngHubConfig()), persistence)

        viewModel.gitLabSettings.updateInstanceUrl("https://gitlab.internal.example")
        viewModel.gitLabSettings.updateToken("glpat-synthetic")
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals(GITLAB_SECRET_PATH_REQUIRED_ERROR, viewModel.uiState.value.gitLabTokenPathError)
        assertTrue(persistence.secrets.isEmpty())
    }

    @Test
    fun validatesTheConfiguredAccountThroughTheInstance() = runTest {
        val persistence = RecordingGitLabPersistence(validatedUsername = "atlas-user")
        val config = EngHubConfig(
            gitLabInstanceUrl = "https://gitlab.internal.example",
            gitLabTokenPath = "/secrets/gitlab.json",
        )
        val viewModel = gitLabSettingsViewModel(
            MutableStateFlow(config),
            persistence,
            loadedGitLabConfig = LoadedGitLabConfig(
                config.gitLabInstanceUrl,
                config.gitLabTokenPath,
                GitLabSecret("saved-token"),
            ),
        )

        viewModel.gitLabSettings.validateAccess()
        runCurrent()
        advanceUntilIdle()

        assertEquals("Connected as @atlas-user", viewModel.uiState.value.gitLabValidationMessage)
        assertEquals(
            GitLabApiRestConfig("https://gitlab.internal.example", "saved-token"),
            persistence.validatedConfigs.single(),
        )
    }

    @Test
    fun reportsAFailedAccountValidationWithoutExposingTheToken() = runTest {
        val persistence = RecordingGitLabPersistence(validationFailure = IllegalStateException("401"))
        val config = EngHubConfig(
            gitLabInstanceUrl = "https://gitlab.internal.example",
            gitLabTokenPath = "/secrets/gitlab.json",
        )
        val viewModel = gitLabSettingsViewModel(
            MutableStateFlow(config),
            persistence,
            loadedGitLabConfig = LoadedGitLabConfig(
                config.gitLabInstanceUrl,
                config.gitLabTokenPath,
                GitLabSecret("saved-token"),
            ),
        )

        viewModel.gitLabSettings.validateAccess()
        runCurrent()
        advanceUntilIdle()

        assertEquals(GITLAB_VALIDATION_FAILURE, viewModel.uiState.value.gitLabValidationMessage)
        assertFalse("saved-token" in viewModel.uiState.value.gitLabValidationMessage.orEmpty())
    }

    @Test
    fun validationRequiresASavedUrlAndToken() = runTest {
        val persistence = RecordingGitLabPersistence()
        val viewModel = gitLabSettingsViewModel(MutableStateFlow(EngHubConfig()), persistence)

        viewModel.gitLabSettings.validateAccess()
        runCurrent()
        advanceUntilIdle()

        assertEquals(GITLAB_ACCESS_NOT_READY_ERROR, viewModel.uiState.value.gitLabValidationMessage)
        assertTrue(persistence.validatedConfigs.isEmpty())
    }

    private fun TestScope.gitLabSettingsViewModel(
        currentConfig: MutableStateFlow<EngHubConfig>,
        persistence: RecordingGitLabPersistence,
        loadedGitLabConfig: LoadedGitLabConfig = LoadedGitLabConfig("", "", GitLabSecret("")),
    ) = EngHubSettingsViewModel(
        engHubConfig = currentConfig.value,
        loadedAccessConfigs = LoadedAccessConfigs(
            LoadedGitHubConfig(GitHubConfig(""), GitHubSecret("")),
            loadedGitLabConfig,
        ),
        directoryPicker = NoopDirectoryPicker,
        filePicker = NoopFilePicker,
        persistence = EngHubSettingsPersistence(
            updateConfig = { transform ->
                transform(currentConfig.value).also { updated ->
                    currentConfig.value = updated
                    persistence.configUpdates += updated
                }
            },
            gitHub = GitHubAccessPersistence(secretWriter = GitHubSecretWriter { _, _ -> }),
            gitLab = GitLabAccessPersistence(
                saveSecret = { path, secret -> persistence.secrets += path to secret },
                onAccessCommitted = { apiConfig -> persistence.committedAccesses += apiConfig },
                validateAccess = { apiConfig -> persistence.validate(apiConfig) },
            ),
        ),
        coroutineScope = backgroundScope,
    )
}

private class RecordingGitLabPersistence(
    private val validatedUsername: String = "atlas-user",
    private val validationFailure: Throwable? = null,
) {
    val secrets = mutableListOf<Pair<Path, GitLabSecret>>()
    val committedAccesses = mutableListOf<GitLabApiRestConfig?>()
    val validatedConfigs = mutableListOf<GitLabApiRestConfig>()
    val configUpdates = mutableListOf<EngHubConfig>()

    suspend fun validate(apiConfig: GitLabApiRestConfig): String {
        validatedConfigs += apiConfig
        validationFailure?.let { throw it }
        return validatedUsername
    }
}

private object NoopDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

private object NoopFilePicker : FilePicker {
    override suspend fun pickFilePath(title: String): String? = null
}
