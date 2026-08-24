package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupCommandEditorTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun removesTheSelectedCommand() = runComposeUiTest {
        var removedIndex: Int? = null
        setContent {
            MaterialTheme {
                SetupCommandEditor(
                    state = SetupCommandEditorState(
                        repositoryIndex = 0,
                        commands = listOf("cp .env.example .env", "direnv allow"),
                        draft = "",
                        error = null,
                    ),
                    actions = SetupCommandEditorActions(onRemove = { removedIndex = it }),
                    modifier = Modifier.size(800.dp, 300.dp),
                )
            }
        }

        onNodeWithTag("repository-0-command-0-remove").performClick()

        assertEquals(0, removedIndex)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsTheSelectedCommand() = runComposeUiTest {
        var editedCommand: Pair<Int, String>? = null
        setContent {
            MaterialTheme {
                SetupCommandEditor(
                    state = SetupCommandEditorState(
                        repositoryIndex = 0,
                        commands = listOf("direnv allow"),
                        draft = "",
                        error = null,
                    ),
                    actions = SetupCommandEditorActions(
                        onCommandChange = { index, command -> editedCommand = index to command },
                    ),
                    modifier = Modifier.size(800.dp, 300.dp),
                )
            }
        }

        onNodeWithTag("repository-0-command-0").performTextReplacement("direnv allow .")

        assertEquals(0 to "direnv allow .", editedCommand)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun addsTheDraftBeforeTheSelectedCommand() = runComposeUiTest {
        var addedAt: Int? = null
        var submittedCommand: String? = null
        setContent {
            var draft by remember { mutableStateOf("") }
            MaterialTheme {
                SetupCommandEditor(
                    state = SetupCommandEditorState(
                        repositoryIndex = 0,
                        commands = listOf("direnv allow"),
                        draft = draft,
                        error = null,
                    ),
                    actions = SetupCommandEditorActions(
                        onDraftChange = { draft = it },
                        onAddAt = {
                            addedAt = it
                            submittedCommand = draft
                        },
                    ),
                    modifier = Modifier.size(800.dp, 300.dp),
                )
            }
        }

        onNodeWithTag("repository-0-command-new").performTextReplacement("cp .env.example .env")
        onNodeWithTag("repository-0-command-0-add-before").performClick()

        assertEquals(0, addedAt)
        assertEquals("cp .env.example .env", submittedCommand)
    }
}
