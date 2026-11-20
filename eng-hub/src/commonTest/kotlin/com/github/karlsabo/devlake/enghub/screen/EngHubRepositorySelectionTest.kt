package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngHubRepositorySelectionTest {
    @Test
    fun selectingWebMountsOnlyWesEditor() {
        val state = settingsState()
        val selectedWeb = initialSettingsNavigationState(state).withSelectedRepository(2)

        val section = state.repositoriesSection(selectedWeb)

        assertEquals("/workspace/web", section.repositoryEditor?.repository?.path)
        assertEquals(1, section.repositoryEditor?.repositoryIndex)
        assertTrue(section.repositoryEditor?.showPath == true)
        assertTrue(section.repositoryEditor?.showSetupCommands == true)
    }

    @Test
    fun unselectedRepositoryHasNoEditableControlsMounted() {
        val state = settingsState()
        val selectedWeb = initialSettingsNavigationState(state).withSelectedRepository(2)

        val section = state.repositoriesSection(selectedWeb)

        assertEquals(listOf("/workspace/api", "/workspace/web"), section.repositoryChooser?.choices?.map { it.path })
        assertEquals("/workspace/web", section.repositoryChooser?.editorRepository?.path)
        assertEquals("/workspace/web", section.repositoryEditor?.repository?.path)
    }

    @Test
    fun renamingTheSelectedPathKeepsTheSameRepositorySelected() {
        val state = settingsState()
        val selectedApi = initialSettingsNavigationState(state).withSelectedRepository(1)
        val renamed = state.withPath(1, "/workspace/api-renamed")

        val section = renamed.repositoriesSection(selectedApi)

        assertEquals(1L, selectedApi.selectedRepositoryId)
        assertEquals(1L, section.repositoryEditor?.repository?.id)
        assertEquals("/workspace/api-renamed", section.repositoryEditor?.repository?.path)
        assertEquals(listOf(1L, 2L), section.repositoryChooser?.choices?.map { it.id })
    }

    @Test
    fun switchingRepositoryEditorsKeepsPendingCommandDrafts() {
        val state = settingsState().withCommandDraft(1, "bootstrap --fast")
        val selectedApi = initialSettingsNavigationState(state).withSelectedRepository(1)

        val afterSwitching = state.repositoriesSection(selectedApi.withSelectedRepository(2))
        val returned = state.repositoriesSection(selectedApi)

        assertEquals("/workspace/web", afterSwitching.repositoryEditor?.repository?.path)
        assertEquals("bootstrap --fast", returned.repositoryEditor?.repository?.setupCommandDraft)
        assertEquals(listOf("prepare"), returned.repositoryEditor?.repository?.setupCommands)
    }

    @Test
    fun emptyRepositoryListShowsTheEmptyStateInsteadOfAnEditor() {
        val state = settingsState(repositories = emptyList())

        val section = state.repositoriesSection()

        assertNull(section.repositoryEditor)
        assertNull(section.repositoryChooser?.selectedRepositoryId)
        assertEquals(emptyList(), section.repositoryChooser?.choices)
        assertTrue(section.repositoryChooser?.isVisible == true)
    }

    @Test
    fun invalidRepositoryIsBadgedInTheChooserWhileAnotherRepositoryIsSelected() {
        val state = settingsState().withPathError(1, "Repository path already exists")
        val selectedWeb = initialSettingsNavigationState(state).withSelectedRepository(2)

        val section = state.repositoriesSection(selectedWeb)

        val apiChoice = section.repositoryChooser?.choices?.first { it.path == "/workspace/api" }
        val webChoice = section.repositoryChooser?.choices?.first { it.path == "/workspace/web" }
        assertTrue(apiChoice?.hasValidationError == true)
        assertEquals("api, /workspace/api, has validation error", apiChoice?.accessibleLabel())
        assertFalse(webChoice?.hasValidationError == true)
        assertEquals("/workspace/web", section.repositoryEditor?.repository?.path)
        assertTrue(EngHubSettingsSection.Repositories in state.sectionsWithValidationErrors())
    }

    @Test
    fun sectionBadgesReportEverySectionWithAValidationError() {
        val state = settingsState().copy(
            gitHubTokenError = "Enter a GitHub token",
            pollIntervalError = "Enter a positive whole number of seconds",
            worktreePollIntervalError = "Enter a positive whole number of seconds",
        )

        val sections = state.sectionsWithValidationErrors()

        assertEquals(
            setOf(
                EngHubSettingsSection.GitHubAccess,
                EngHubSettingsSection.GitHubActivity,
                EngHubSettingsSection.Repositories,
            ),
            sections,
        )
        assertEquals("Repositories, has validation error", EngHubSettingsSection.Repositories.accessibleLabel(true))
        assertEquals("Repositories", EngHubSettingsSection.Repositories.accessibleLabel(false))
    }

    @Test
    fun badgesStayVisibleWhileAFilteredSearchHidesTheInvalidEditor() {
        val state = settingsState().withPathError(1, "Repository path already exists")
        val searching = initialSettingsNavigationState(state).withQuery("worktree polling")

        val plan = state.planFor(searching)

        assertEquals(listOf(EngHubSettingGroupId.WorktreePollInterval), plan.sections.single().groups)
        assertNull(plan.sections.single().repositoryEditor)
        assertTrue(EngHubSettingsSection.Repositories in state.sectionsWithValidationErrors())
    }
}

private fun EngHubSettingsUiState.withPathError(
    repositoryId: Long,
    error: String,
): EngHubSettingsUiState = copy(
    localRepositories = localRepositories.map { repository ->
        if (repository.id == repositoryId) repository.copy(pathError = error) else repository
    },
)

private fun EngHubSettingsUiState.withCommandDraft(
    repositoryId: Long,
    command: String,
): EngHubSettingsUiState = copy(
    localRepositories = localRepositories.map { repository ->
        if (repository.id == repositoryId) repository.copy(setupCommandDraft = command) else repository
    },
)
