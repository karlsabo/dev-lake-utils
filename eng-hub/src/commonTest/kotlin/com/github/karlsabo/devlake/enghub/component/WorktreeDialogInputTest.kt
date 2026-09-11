package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeDialogInputTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun createWorktreeDialogKeepsRapidInputAndCaretPosition() = runComposeUiTest {
        var publishedTargetBranch = ""
        setContent {
            var targetBranch by remember { mutableStateOf("") }
            MaterialTheme {
                CreateWorktreeTargetBranchField(
                    targetBranch = targetBranch,
                    validationMessage = null,
                    onTargetBranchInputChange = {
                        targetBranch = it
                        publishedTargetBranch = it
                    },
                )
            }
        }

        onNodeWithTag("create-worktree-target-branch").performClick()
        onNodeWithTag("create-worktree-target-branch").performTextInput("1234")

        onNodeWithTag("create-worktree-target-branch")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("1234")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4)))
        runOnIdle { assertEquals("1234", publishedTargetBranch) }
    }
}
