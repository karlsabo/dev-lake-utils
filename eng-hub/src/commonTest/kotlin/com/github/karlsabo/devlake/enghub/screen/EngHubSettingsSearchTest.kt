package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngHubSettingsSearchTest {
    @Test
    fun pollingQueryShowsBothPollingGroupsUnderTheirSectionHeadings() {
        val state = settingsState()
        val onGitHubAccess = initialSettingsNavigationState(state).withSection(EngHubSettingsSection.GitHubAccess)

        val plan = state.planFor(onGitHubAccess.withQuery("polling"))

        assertEquals(
            listOf(EngHubSettingsSection.GitHubActivity, EngHubSettingsSection.Repositories),
            plan.sections.map { it.section },
        )
        assertEquals(
            listOf(EngHubSettingGroupId.ActivityPollInterval),
            plan.sections.first().groups,
        )
        assertEquals(
            listOf(EngHubSettingGroupId.WorktreePollInterval),
            plan.sections.last().groups,
        )
        assertNull(plan.sections.last().repositoryChooser)
        assertNull(plan.sections.last().repositoryEditor)
    }

    @Test
    fun matchingIsCaseInsensitiveAndSupportsAliases() {
        val state = settingsState()

        val refresh = state.planFor(initialSettingsNavigationState(state).withQuery("REFRESH"))
        val repositories = state.planFor(initialSettingsNavigationState(state).withQuery("repo"))

        assertEquals(
            listOf(EngHubSettingGroupId.ActivityPollInterval, EngHubSettingGroupId.WorktreePollInterval),
            refresh.sections.flatMap { section -> section.groups },
        )
        assertEquals(
            listOf(EngHubSettingsSection.Repositories),
            repositories.sections.map { section -> section.section },
        )
        assertTrue(repositories.sections.single().repositoryChooser?.isVisible == true)
    }

    @Test
    fun emptyQueryNeverReportsNoMatches() {
        val state = settingsState()

        val plan = state.planFor(initialSettingsNavigationState(state))

        assertFalse(plan.noMatches)
        assertEquals(1, plan.sections.size)
    }

    @Test
    fun unmatchedQueryReportsNoMatches() {
        val state = settingsState()

        val plan = state.planFor(initialSettingsNavigationState(state).withQuery("zz-no-such-setting"))

        assertTrue(plan.noMatches)
        assertTrue(plan.sections.isEmpty())
    }

    @Test
    fun searchNeverMatchesTokenCommandOrTemplateValues() {
        val state = settingsState(
            gitHubToken = "ghp_synthetic_secret",
            config = EngHubConfig(
                llmTemplateValues = mapOf(ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to "secret-template-guidance"),
            ),
        ).withCommandDraft("secret-command --flag")

        listOf("ghp_synthetic_secret", "secret-command", "secret-template-guidance").forEach { query ->
            val plan = state.planFor(initialSettingsNavigationState(state).withQuery(query))
            assertTrue(plan.noMatches, "configured values must never be searchable")
        }
    }

    @Test
    fun repositoryChoicesMatchNameAndFullPath() {
        val state = settingsState(
            repositories = listOf(
                LocalRepositoryConfig(path = "/workspace/api"),
                LocalRepositoryConfig(path = "/workspace/payments-api"),
            ),
        )

        val plan = state.planFor(initialSettingsNavigationState(state).withQuery("payments"))

        val chooser = plan.sections.single().repositoryChooser
        assertEquals(listOf("payments-api"), chooser?.choices?.map { choice -> choice.name })
        assertEquals(listOf("/workspace/payments-api"), chooser?.choices?.map { choice -> choice.path })
        assertTrue(plan.sections.single().groups.isEmpty())
    }

    @Test
    fun equalBasenamesStayDistinguishableByFullPath() {
        val state = settingsState(
            repositories = listOf(
                LocalRepositoryConfig(path = "/workspace/team-a/api"),
                LocalRepositoryConfig(path = "/workspace/team-b/api"),
            ),
        )

        val choices = state.planFor(initialSettingsNavigationState(state).withQuery("api"))
            .sections.single().repositoryChooser?.choices.orEmpty()

        assertEquals(listOf("api", "api"), choices.map { choice -> choice.name })
        assertEquals(
            listOf("/workspace/team-a/api", "/workspace/team-b/api"),
            choices.map { choice -> choice.path },
        )
        assertEquals("api, /workspace/team-a/api", choices.first().accessibleLabel())
    }

    @Test
    fun choosingARepositoryDuringSearchOpensItsEditorAndKeepsTheQuery() {
        val state = settingsState(
            repositories = listOf(
                LocalRepositoryConfig(path = "/workspace/api"),
                LocalRepositoryConfig(path = "/workspace/payments-api"),
            ),
        )
        val searching = initialSettingsNavigationState(state).withQuery("payments")

        val chosen = searching.withSelectedRepository(2)
        val section = state.repositoriesSection(chosen)

        assertEquals("payments", chosen.query)
        assertEquals("/workspace/payments-api", section.repositoryEditor?.repository?.path)
        assertEquals(listOf(2L), section.repositoryChooser?.choices?.map { choice -> choice.id })
    }

    @Test
    fun clearingTheQueryAfterChoosingARepositoryKeepsTheChosenRepository() {
        val state = settingsState()
        val beforeSearch = initialSettingsNavigationState(state).withSelectedRepository(1)

        val cleared = beforeSearch.withQuery("web").withSelectedRepository(2).clearedQuery()

        assertEquals("", cleared.query)
        assertEquals(2L, cleared.selectedRepositoryId)
        assertEquals(EngHubSettingsSection.Repositories, cleared.section)
    }

    @Test
    fun clearingTheQueryAfterChoosingARepositoryFromAnotherSectionOpensRepositories() {
        val state = settingsState()
        val beforeSearch = initialSettingsNavigationState(state)
            .withSection(EngHubSettingsSection.PlanningAndSetup)

        val cleared = beforeSearch.withQuery("web").withSelectedRepository(2).clearedQuery()

        assertEquals("", cleared.query)
        assertEquals(EngHubSettingsSection.Repositories, cleared.section)
        assertEquals(2L, cleared.selectedRepositoryId)
        assertEquals("/workspace/web", state.repositoriesSection(cleared).repositoryEditor?.repository?.path)
    }

    @Test
    fun clearingTheQueryWithoutAnExplicitSelectionRestoresThePreSearchDestination() {
        val state = settingsState()
        val beforeSearch = initialSettingsNavigationState(state)
            .withSection(EngHubSettingsSection.PlanningAndSetup)
            .withSelectedRepository(2)

        val cleared = beforeSearch.withQuery("zz-no-such-setting").clearedQuery()

        assertEquals("", cleared.query)
        assertEquals(EngHubSettingsSection.PlanningAndSetup, cleared.section)
        assertEquals(2L, cleared.selectedRepositoryId)
        assertEquals(
            "/workspace/web",
            state.repositoriesSection(cleared.withSection(EngHubSettingsSection.Repositories))
                .repositoryEditor?.repository?.path,
        )
    }

    @Test
    fun clearingAnEmptyQueryChangesNothing() {
        val navigation = initialSettingsNavigationState(settingsState())

        assertEquals(navigation, navigation.clearedQuery())
    }

    @Test
    fun sectionHeadingsAreHiddenWhenTheSectionHasNoMatchingGroup() {
        val state = settingsState()

        val plan = state.planFor(initialSettingsNavigationState(state).withQuery("setup shell"))

        assertEquals(listOf(EngHubSettingsSection.PlanningAndSetup), plan.sections.map { it.section })
        assertEquals(listOf(EngHubSettingGroupId.SetupShell), plan.sections.single().groups)
    }

    @Test
    fun setupCommandSearchMountsTheSelectedRepositoryCommands() {
        val state = settingsState()

        val plan = state.planFor(initialSettingsNavigationState(state).withQuery("bootstrap"))

        val section = plan.sections.single()
        assertEquals(listOf(EngHubSettingGroupId.RepositorySetupCommands), section.groups)
        assertNull(section.repositoryChooser)
        assertEquals("/workspace/api", section.repositoryEditor?.repository?.path)
        assertTrue(section.repositoryEditor?.showSetupCommands == true)
        assertFalse(section.repositoryEditor?.showPath == true)
    }
}

private fun EngHubSettingsUiState.withCommandDraft(command: String): EngHubSettingsUiState = copy(
    localRepositories = localRepositories.map { repository -> repository.copy(setupCommandDraft = command) },
)
