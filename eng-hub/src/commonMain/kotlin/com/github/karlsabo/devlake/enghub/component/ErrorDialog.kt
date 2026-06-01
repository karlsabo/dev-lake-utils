package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun errorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Error",
        icon = painterResource(Res.drawable.icon),
        visible = true,
        state = rememberDialogState(width = 720.dp, height = 520.dp),
    ) {
        MaterialTheme {
            Surface {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .widthIn(min = 480.dp, max = 720.dp),
                ) {
                    Text(text = "Error", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    errorMessageBody(
                        message = message,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onDismiss) {
                        Text("Ok")
                    }
                }
            }
        }
    }
}

@Composable
private fun errorMessageBody(
    message: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 12.dp)
                .verticalScroll(scrollState),
        )
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState),
        )
    }
}
