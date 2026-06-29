package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class ErrorDialogTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun errorDialogContentShowsFullErrorMessage() = runComposeUiTest {
        val message = "Setup commands failed\nstdout line\nstderr line"

        setContent {
            MaterialTheme {
                ErrorDialogContent(
                    message = message,
                    onDismiss = {},
                )
            }
        }

        onNodeWithText(message).assertIsDisplayed()
        onNodeWithText("Ok").assertIsDisplayed()
    }
}
