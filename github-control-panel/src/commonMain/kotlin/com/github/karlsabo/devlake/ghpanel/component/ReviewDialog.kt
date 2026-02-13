package com.github.karlsabo.devlake.ghpanel.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.github.karlsabo.github.ReviewStateValue
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun ReviewDialog(
    onSubmit: (event: ReviewStateValue, reviewComment: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedEvent by remember { mutableStateOf(ReviewStateValue.COMMENTED) }
    var reviewComment by remember { mutableStateOf("") }

    val events = listOf(
        ReviewStateValue.COMMENTED to "Comment",
        ReviewStateValue.CHANGES_REQUESTED to "Request Changes",
        ReviewStateValue.APPROVED to "Approve",
    )

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Submit Review",
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                ) {
                    Text(text = "Review Type", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))

                    events.forEach { (event, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedEvent == event,
                                onClick = { selectedEvent = event },
                            )
                            Text(text = label)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = reviewComment,
                        onValueChange = { reviewComment = it },
                        label = { Text("Review comment") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row {
                        Button(
                            onClick = {
                                onSubmit(selectedEvent, reviewComment.ifBlank { null })
                            },
                            enabled = selectedEvent == ReviewStateValue.APPROVED || reviewComment.isNotBlank(),
                        ) {
                            Text("Submit")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
