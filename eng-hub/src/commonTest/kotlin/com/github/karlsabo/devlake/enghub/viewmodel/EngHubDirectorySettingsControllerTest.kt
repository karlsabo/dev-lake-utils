package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubDirectorySettingsControllerTest {
    @Test
    fun choosingANewRepositoryDirectoryFillsTheDraftWithoutAddingIt() = runTest {
        val writer = RecordingConfigWriter()
        val picker = SingleChoiceDirectoryPicker("/workspace/new")
        val viewModel = settingsViewModel(
            writer = writer,
            configState = MutableConfigState(EngHubConfig()),
            dependencies = SettingsViewModelDependencies(directoryPicker = picker),
        )

        viewModel.directorySettings.chooseLocalRepositoryDraft()
        runCurrent()

        assertEquals(listOf(LOCAL_REPOSITORY_PICKER_TITLE), picker.titles)
        assertEquals("/workspace/new", viewModel.uiState.value.localRepositoryDraft)
        assertTrue(viewModel.uiState.value.localRepositories.isEmpty())
        assertTrue(writer.savedConfigs.isEmpty())
    }

    @Test
    fun cancellingTheNewRepositoryPickerKeepsTheTypedDraft() = runTest {
        val viewModel = settingsViewModel(
            writer = RecordingConfigWriter(),
            configState = MutableConfigState(EngHubConfig()),
            dependencies = SettingsViewModelDependencies(directoryPicker = SingleChoiceDirectoryPicker(null)),
        )
        viewModel.localRepositorySettings.updateDraft("/workspace/typed")

        viewModel.directorySettings.chooseLocalRepositoryDraft()
        runCurrent()

        assertEquals("/workspace/typed", viewModel.uiState.value.localRepositoryDraft)
    }
}

private class SingleChoiceDirectoryPicker(
    private val selectedPath: String?,
) : DirectoryPicker {
    val titles = mutableListOf<String>()

    override suspend fun pickDirectory(title: String): String? {
        titles += title
        return selectedPath
    }
}
