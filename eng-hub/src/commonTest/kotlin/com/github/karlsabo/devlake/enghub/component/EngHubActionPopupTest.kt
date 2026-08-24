package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class EngHubActionPopupTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun invokesFilteredActionWithKeyboard() = runComposeUiTest {
        setContent {
            var expanded by remember { mutableStateOf(true) }
            var invokedAction by remember { mutableStateOf<String?>(null) }
            MaterialTheme {
                Text("Invoked: ${invokedAction.orEmpty()}")
                EngHubActionPopup(
                    expanded = expanded,
                    actions = listOf(
                        EngHubAction("Settings") { invokedAction = "Settings" },
                        EngHubAction("Notifications") { invokedAction = "Notifications" },
                    ),
                    onDismissRequest = { expanded = false },
                    onDismissByKeyboard = { expanded = false },
                )
            }
        }

        onNodeWithTag("action-search").assertIsFocused().performTextInput("setings")
        onNodeWithTag("action-search").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithText("Settings").assertIsSelected()

        onNodeWithTag("action-search").performKeyInput { pressKey(Key.Enter) }

        onNodeWithText("Invoked: Settings").assertExists()
    }
}
