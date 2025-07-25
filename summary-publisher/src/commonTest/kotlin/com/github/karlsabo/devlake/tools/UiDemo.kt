@file:OptIn(ExperimentalUuidApi::class)

package com.github.karlsabo.devlake.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
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

data class ProjectMessage(
    val id: Uuid,
    val text: String
)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "UI Demo",
        visible = true,
        state = rememberWindowState(
            width = 1920.dp,
            height = 1080.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        var zapierJson by remember { mutableStateOf("Loading summary") }
        rememberScrollState()
        var projectMessages by remember {
            mutableStateOf(
                mutableListOf(
                    ProjectMessage(Uuid.random(), "Project A"),
                    ProjectMessage(Uuid.random(), "Project B"),
                )
            )
        }

        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(
                    onClick = { println("helo moto") },
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text("Hello")
                }
                TextField(
                    value = zapierJson,
                    onValueChange = { zapierJson = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )

                LazyColumn {
                    itemsIndexed(
                        items = projectMessages,
                        key = { _, item -> item.id } // the key is the unique ID!
                    ) { index, project ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = project.text,
                                onValueChange = { newValue ->
                                    projectMessages = projectMessages.toMutableList().also {
                                        it[index] = it[index].copy(text = newValue)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    projectMessages = projectMessages.toMutableList().also { it.removeAt(index) }
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
