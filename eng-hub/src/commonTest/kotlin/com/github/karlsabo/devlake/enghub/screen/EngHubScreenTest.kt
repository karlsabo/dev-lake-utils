package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

class EngHubScreenTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fuzzySettingsActionClosesPopupAndSelectsSettingsPane() = runComposeUiTest {
        setContent {
            var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
            MaterialTheme {
                EngHubScreenHeader(
                    selectedPane = selectedPane,
                    onPaneSelect = { selectedPane = it },
                )
            }
        }

        onNodeWithContentDescription("Open actions").performClick()
        onNodeWithText("Search actions…").performTextInput("setings")
        onNodeWithText("Settings").assertIsDisplayed().performClick()

        onAllNodesWithText("Search actions…").assertCountEquals(0)
        onAllNodesWithText("Settings").assertCountEquals(1)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bottomGearSelectsSettingsPane() = runComposeUiTest {
        setContent {
            var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
            MaterialTheme {
                Box(modifier = Modifier.size(width = 240.dp, height = 300.dp)) {
                    EngHubSidebar(
                        selectedPane = selectedPane,
                        onPaneSelect = { selectedPane = it },
                    )
                    Text(
                        text = "Selected: ${selectedPane.label}",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        val notifications = onNodeWithContentDescription("Notifications").fetchSemanticsNode().boundsInRoot
        val worktrees = onNodeWithContentDescription("Worktrees").fetchSemanticsNode().boundsInRoot
        val settings = onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue(settings.top - worktrees.bottom > worktrees.top - notifications.bottom)

        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("Selected: Settings").assertIsDisplayed()
    }
}
