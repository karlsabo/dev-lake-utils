package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.representativeEngHubConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Rendered bounds and accessibility checks for the settings shell at desktop and constrained pane widths. */
@OptIn(ExperimentalTestApi::class)
class EngHubSettingsRenderedLayoutTest {
    @Test
    fun browseButtonCentersOnTheTextboxWhileAPathErrorIsShown() = runSettingsUiTest {
        val state = repositoriesState().withPathError("Repository path already exists")
        setContentInPane(DESKTOP_PANE_WIDTH, DESKTOP_PANE_HEIGHT, state)

        val fieldBounds = onNodeWithTag("repository-0-path").getUnclippedBoundsInRoot()
        val browseBounds = onNodeWithTag("repository-0-path-browse").getUnclippedBoundsInRoot()
        val fieldCenter = (fieldBounds.top.value + fieldBounds.bottom.value) / 2
        val browseCenter = (browseBounds.top.value + browseBounds.bottom.value) / 2

        onNodeWithTag("repository-0-path-error").assertIsDisplayed()
        assertTrue(
            abs(fieldCenter - browseCenter) <= 1f,
            "Browse center $browseCenter must align with textbox center $fieldCenter",
        )
    }

    @Test
    fun repositoryPathAndActionsStayReachableAtA1000By700Window() = runSettingsUiTest {
        setContentInPane(SMALL_PANE_WIDTH, SMALL_PANE_HEIGHT, repositoriesState())

        onNodeWithTag("repository-0-path").assertIsDisplayed()
        onNodeWithTag("repository-0-path-browse").assertIsDisplayed()
        onNodeWithTag("repository-0-remove").assertIsDisplayed()

        val browseBounds = onNodeWithTag("repository-0-path-browse").getUnclippedBoundsInRoot()
        val removeBounds = onNodeWithTag("repository-0-remove").getUnclippedBoundsInRoot()
        assertTrue(browseBounds.right.value <= SMALL_PANE_WIDTH.value)
        assertTrue(removeBounds.right.value <= SMALL_PANE_WIDTH.value)
    }

    @Test
    fun narrowPaneStacksPathActionsBelowTheTextbox() = runSettingsUiTest {
        setContentInPane(COMPACT_PANE_WIDTH, SMALL_PANE_HEIGHT, repositoriesState())

        onNodeWithTag("repository-0-path-browse").performScrollTo()
        val fieldBounds = onNodeWithTag("repository-0-path").getUnclippedBoundsInRoot()
        val browseBounds = onNodeWithTag("repository-0-path-browse").getUnclippedBoundsInRoot()

        onNodeWithTag("repository-0-path").assertIsDisplayed()
        onNodeWithTag("repository-0-path-browse").assertIsDisplayed()
        assertTrue(
            browseBounds.top.value >= fieldBounds.bottom.value,
            "Browse must stack below the textbox on a narrow pane",
        )
    }

    @Test
    fun repositoryListSitsBesideTheSelectedRepositoryDetailsOnAWidePane() = runSettingsUiTest {
        val state = repositoriesState()
        setContentInPane(DESKTOP_PANE_WIDTH, DESKTOP_PANE_HEIGHT, state)

        val listBounds = onNodeWithTag("repository-chooser").getUnclippedBoundsInRoot()
        val pathBounds = onNodeWithTag("repository-0-path").getUnclippedBoundsInRoot()

        onNodeWithTag("repository-new-browse").assertIsDisplayed()
        onNodeWithTag("repository-choice-${state.localRepositories.first().id}").assertIsSelected()
        assertTrue(
            listBounds.right.value <= pathBounds.left.value,
            "Repository list must sit left of the selected repository details",
        )
    }

    @Test
    fun narrowPaneStacksTheRepositoryListAboveTheSelectedRepositoryDetails() = runSettingsUiTest {
        setContentInPane(COMPACT_PANE_WIDTH, SMALL_PANE_HEIGHT, repositoriesState())

        onNodeWithTag("repository-0-path").performScrollTo()
        val listBounds = onNodeWithTag("repository-chooser").getUnclippedBoundsInRoot()
        val pathBounds = onNodeWithTag("repository-0-path").getUnclippedBoundsInRoot()

        assertTrue(
            listBounds.bottom.value <= pathBounds.top.value,
            "Repository list must stack above the selected repository details on a narrow pane",
        )
    }

    @Test
    fun sectionNavigationMarksTheSelectedSection() = runSettingsUiTest {
        setContentInPane(DESKTOP_PANE_WIDTH, DESKTOP_PANE_HEIGHT, repositoriesState())

        onNodeWithTag("settings-section-Repositories").assertIsSelected()
        onNodeWithTag("settings-section-GitHubAccess").assertIsNotSelected()
    }

    @Test
    fun clearSearchActionIsAccessibleAndOnlyShownWithAQuery() = runSettingsUiTest {
        setContentInPane(DESKTOP_PANE_WIDTH, DESKTOP_PANE_HEIGHT, repositoriesState())

        onNodeWithTag("settings-search-clear").assertDoesNotExist()
        onNodeWithTag("settings-search").performTextInput("polling")

        onNodeWithTag("settings-search-clear").assertContentDescriptionEquals(CLEAR_SEARCH_LABEL)
        onNodeWithTag("settings-search-clear").performClick()

        onNodeWithTag("settings-search-clear").assertDoesNotExist()
        onNodeWithTag("repository-0-path").assertIsDisplayed()
    }

    @Test
    fun repositoryBadgeNamesTheValidationErrorInTheSectionNavigation() = runSettingsUiTest {
        val state = repositoriesState().withPathError("Repository path already exists")
        setContentInPane(DESKTOP_PANE_WIDTH, DESKTOP_PANE_HEIGHT, state)

        onNodeWithTag("settings-section-Repositories")
            .assertContentDescriptionEquals("Repositories, $VALIDATION_ERROR_SUFFIX")
        onNodeWithTag("settings-section-error-Repositories", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun runSettingsUiTest(block: suspend ComposeUiTest.() -> Unit) {
        runDesktopComposeUiTest(width = SCENE_WIDTH, height = SCENE_HEIGHT, block = block)
    }

    private fun ComposeUiTest.setContentInPane(
        width: androidx.compose.ui.unit.Dp,
        height: androidx.compose.ui.unit.Dp,
        state: EngHubSettingsUiState,
    ) {
        val navigation = EngHubSettingsNavigation(initialSettingsNavigationState(state))
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(width, height)) {
                    EngHubSettingsScreen(state = state, navigation = navigation)
                }
            }
        }
    }

    private companion object {
        val DESKTOP_PANE_WIDTH = 1311.dp
        val DESKTOP_PANE_HEIGHT = 768.dp
        val SMALL_PANE_WIDTH = 911.dp
        val SMALL_PANE_HEIGHT = 668.dp
        val COMPACT_PANE_WIDTH = 600.dp
        const val SCENE_WIDTH = 1400
        const val SCENE_HEIGHT = 900
    }
}

private fun repositoriesState(): EngHubSettingsUiState = createEngHubSettingsUiState(
    engHubConfig = representativeEngHubConfig(),
    gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
    gitHubSecret = GitHubSecret(githubToken = "token"),
)

private fun EngHubSettingsUiState.withPathError(error: String): EngHubSettingsUiState = copy(
    localRepositories = localRepositories.mapIndexed { index, repository ->
        if (index == 0) repository.copy(pathError = error) else repository
    },
)
