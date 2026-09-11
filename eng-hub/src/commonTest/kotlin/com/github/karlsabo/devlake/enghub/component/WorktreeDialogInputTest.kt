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

class WorktreeDialogInputTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun createWorktreeDialogKeepsCaretAfterEachTypedCharacter() = runComposeUiTest {
        setContent {
            var targetBranch by remember { mutableStateOf("") }
            MaterialTheme {
                CreateWorktreeTargetBranchField(
                    targetBranch = targetBranch,
                    validationMessage = null,
                    onTargetBranchInputChange = { targetBranch = it },
                )
            }
        }

        onNodeWithTag("create-worktree-target-branch").performClick()
        onNodeWithTag("create-worktree-target-branch").performTextInput("a")
        onNodeWithTag("create-worktree-target-branch").performTextInput("b")
        onNodeWithTag("create-worktree-target-branch").performTextInput("c")

        onNodeWithTag("create-worktree-target-branch")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("abc")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
    }
}
