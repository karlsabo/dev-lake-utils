package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngHubSettingsNavigationTest {
    @Test
    fun firstRunSettingsOpensGitHubAccess() {
        val state = settingsState(gitHubTokenPath = "", gitHubToken = "")

        assertEquals(EngHubSettingsSection.GitHubAccess, initialSettingsNavigationState(state).section)
        assertEquals(EngHubSettingsSection.Repositories, initialSettingsNavigationState(settingsState()).section)
    }

    @Test
    fun emptyQueryRendersOnlyTheSelectedSection() {
        val state = settingsState()
        val planning = initialSettingsNavigationState(state).withSection(EngHubSettingsSection.PlanningAndSetup)

        val plan = state.planFor(planning)

        assertEquals(listOf(EngHubSettingsSection.PlanningAndSetup), plan.sections.map { it.section })
        assertEquals(
            listOf(EngHubSettingGroupId.PlanningMarkdownDirectory, EngHubSettingGroupId.SetupShell),
            plan.sections.single().groups,
        )
    }

    @Test
    fun sectionAndRepositorySelectionSurviveForTheSession() {
        val state = settingsState()
        val navigation = EngHubSettingsNavigation(initialSettingsNavigationState(state))

        navigation.selectSection(EngHubSettingsSection.LlmSkillTemplates)
        navigation.selectRepository(2)

        assertEquals(EngHubSettingsSection.LlmSkillTemplates, navigation.state.section)
        assertEquals(2L, navigation.state.selectedRepositoryId)
    }

    @Test
    fun repositoriesSectionSelectsTheFirstRepository() {
        val state = settingsState()

        val section = state.repositoriesSection()

        assertEquals(
            listOf(
                EngHubSettingGroupId.RepositoriesBaseDirectory,
                EngHubSettingGroupId.WorktreePollInterval,
                EngHubSettingGroupId.Repositories,
                EngHubSettingGroupId.RepositorySetupCommands,
            ),
            section.groups,
        )
        assertEquals(1L, section.repositoryEditor?.repository?.id)
        assertEquals("/workspace/api", section.repositoryEditor?.repository?.path)
        assertEquals(listOf(1L, 2L), section.repositoryChooser?.choices?.map { choice -> choice.id })
    }

    @Test
    fun removedSelectedRepositorySelectsTheNextThenThePreviousEntry() {
        val state = settingsState(
            repositories = listOf(
                LocalRepositoryConfig(path = "/workspace/api"),
                LocalRepositoryConfig(path = "/workspace/web"),
                LocalRepositoryConfig(path = "/workspace/docs"),
            ),
        )
        val selectedMiddle = initialSettingsNavigationState(state).withSelectedRepository(2)

        val afterMiddleRemoval = selectedMiddle.withRepositoryIds(listOf(1L, 3L))
        val afterThirdRemoval = afterMiddleRemoval.withRepositoryIds(listOf(1L))

        assertEquals(3L, afterMiddleRemoval.selectedRepositoryId)
        assertEquals(1L, afterThirdRemoval.selectedRepositoryId)
    }

    @Test
    fun removalOfEveryRepositoryFallsBackToTheEmptyState() {
        val state = settingsState()
        val navigation = initialSettingsNavigationState(state)

        val empty = navigation.withRepositoryIds(emptyList())

        assertNull(empty.selectedRepositoryId)
        assertNull(state.copy(localRepositories = emptyList()).repositoriesSection(empty).repositoryEditor)
        val emptyState = state.copy(localRepositories = emptyList())
        assertTrue(emptyState.repositoriesSection(empty).repositoryChooser?.isVisible == true)
    }

    @Test
    fun addingARepositorySelectsTheNewEntry() {
        val state = settingsState()
        val navigation = EngHubSettingsNavigation(initialSettingsNavigationState(state))
        val addedState = settingsState(
            repositories = listOf(
                LocalRepositoryConfig(path = "/workspace/api"),
                LocalRepositoryConfig(path = "/workspace/web"),
                LocalRepositoryConfig(path = "/workspace/docs"),
            ),
        )

        navigation.reconcileRepositories(addedState.localRepositories)
        navigation.selectRepository(3)

        assertEquals(3L, addedState.repositoriesSection(navigation.state).repositoryEditor?.repository?.id)
    }

    @Test
    fun undoingARemovalSelectsTheRestoredRepository() {
        val state = settingsState()
        val removed = state.copy(localRepositories = state.localRepositories.filter { it.id == 2L })
        val navigation = EngHubSettingsNavigation(initialSettingsNavigationState(removed))
        assertEquals(2L, navigation.state.selectedRepositoryId)

        navigation.requestRepositorySelectionByPath("/workspace/api")
        navigation.reconcileRepositories(state.localRepositories)

        assertEquals(1L, navigation.state.selectedRepositoryId)
        assertEquals("/workspace/api", state.repositoriesSection(navigation.state).repositoryEditor?.repository?.path)
    }

    @Test
    fun repositoryIdentityOutsideTheCurrentListIsIgnored() {
        val state = settingsState()

        val navigation = initialSettingsNavigationState(state).withSelectedRepository(99)

        assertEquals(1L, navigation.selectedRepositoryId)
    }

    @Test
    fun selectingASectionDuringSearchEndsTheSearch() {
        val state = settingsState()
        val searching = initialSettingsNavigationState(state).withQuery("polling")

        val selected = searching.withSection(EngHubSettingsSection.GitHubAccess)

        assertEquals("", selected.query)
        assertEquals(EngHubSettingsSection.GitHubAccess, selected.section)
        assertNull(selected.preSearchDestination)
    }
}
