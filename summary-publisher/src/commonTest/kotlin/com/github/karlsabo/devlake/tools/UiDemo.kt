@file:OptIn(ExperimentalUuidApi::class)

package com.github.karlsabo.devlake.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun main() = application {
    UiDemoWindow(onCloseRequest = ::exitApplication)
}

@Composable
private fun UiDemoWindow(onCloseRequest: () -> Unit) {
    Window(
        onCloseRequest = onCloseRequest,
        title = "UI Demo",
        visible = true,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        MaterialTheme {
            UiDemoContent()
        }
    }
}

@Composable
private fun UiDemoContent() {
    var zapierJson by remember { mutableStateOf("Loading summary") }
    var projectMessages by remember { mutableStateOf(initialProjectMessages()) }

    Column(modifier = Modifier.fillMaxSize()) {
        DemoButton()
        TextField(
            value = zapierJson,
            onValueChange = { zapierJson = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        ProjectMessagesEditor(
            projectMessages = projectMessages,
            onProjectMessagesChange = { projectMessages = it },
        )
    }
}

@Composable
private fun DemoButton() {
    Button(
        onClick = { println("helo moto") },
        modifier = Modifier.padding(8.dp),
    ) {
        Text("Hello")
    }
}

@Composable
private fun ProjectMessagesEditor(
    projectMessages: List<ProjectMessage>,
    onProjectMessagesChange: (List<ProjectMessage>) -> Unit,
) {
    LazyColumn {
        itemsIndexed(
            items = projectMessages,
            key = { _, item -> item.id },
        ) { index, project ->
            ProjectMessageRow(
                project = project,
                onTextChange = { newValue ->
                    onProjectMessagesChange(
                        projectMessages.replaceAt(index, project.copy(text = newValue)),
                    )
                },
                onDelete = {
                    onProjectMessagesChange(projectMessages.withoutIndex(index))
                },
            )
        }
    }
}

@Composable
private fun ProjectMessageRow(
    project: ProjectMessage,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = project.text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onDelete,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text("Delete")
        }
    }
}

private fun initialProjectMessages() = listOf(
    ProjectMessage(Uuid.random(), "Project A"),
    ProjectMessage(Uuid.random(), "Project B"),
)

private fun List<ProjectMessage>.replaceAt(index: Int, value: ProjectMessage) = toMutableList().also {
    it[index] = value
}

private fun List<ProjectMessage>.withoutIndex(index: Int) = toMutableList().also { it.removeAt(index) }
