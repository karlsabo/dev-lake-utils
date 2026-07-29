package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

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
}
