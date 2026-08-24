package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class PullRequestPanelTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emptyOrganizationsShowSettingsGuidance() = runComposeUiTest {
        setContent {
            MaterialTheme {
                PullRequestPanel(
                    pullRequestsResult = Result.success(emptyList()),
                    organizationIdsEmpty = true,
                    actions = PullRequestPanelActions(
                        onOpenInBrowser = {},
                        onCheckoutAndOpen = { _, _ -> },
                        setupStatusFor = { _, _ -> null },
                    ),
                )
            }
        }

        onNodeWithText("Add at least one organization in Settings to search for pull requests").assertIsDisplayed()
    }
}
