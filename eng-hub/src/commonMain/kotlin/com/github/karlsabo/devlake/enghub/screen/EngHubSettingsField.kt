package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedSecureTextField
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.rememberSyncedTextFieldState
import com.github.karlsabo.devlake.enghub.state.MASKED_TOKEN

/** Settings pane width below which section navigation and field actions stack instead of sitting side by side. */
internal val SETTINGS_COMPACT_WIDTH = 720.dp

internal data class SettingsFieldPresentation(
    val password: Boolean = false,
    val error: String? = null,
    val singleLine: Boolean = true,
)

internal data class SettingsPickerPresentation(
    val error: String? = null,
    val compact: Boolean = false,
)

internal data class SettingsPickerActions(
    val onValueChange: (String) -> Unit = {},
    val onBrowse: () -> Unit = {},
    val browseLabel: String = "Browse",
)

/**
 * A setting control with its validation message below the whole control row, so a shown error never shifts an
 * aligned action away from the textbox it belongs to.
 */
@Composable
internal fun SettingsField(
    label: String,
    value: String,
    tag: String,
    presentation: SettingsFieldPresentation = SettingsFieldPresentation(),
    onValueChange: ((String) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsTextField(
            label = label,
            value = value,
            tag = tag,
            presentation = presentation,
            onValueChange = onValueChange,
        )
        SettingsFieldError(presentation.error, "$tag-error")
    }
}

@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    tag: String,
    presentation: SettingsFieldPresentation = SettingsFieldPresentation(),
    onValueChange: ((String) -> Unit)? = null,
) {
    val fieldState = rememberSyncedTextFieldState(value, onValueChange ?: {})
    val fieldModifier = Modifier.fillMaxWidth().testTag(tag).secureTextSemantics(value, presentation.password)
    if (presentation.password) {
        OutlinedSecureTextField(
            state = fieldState,
            label = { Text(label) },
            isError = presentation.error != null,
            inputTransformation = RejectAllInput.takeIf { onValueChange == null },
            textObfuscationMode = TextObfuscationMode.Hidden,
            modifier = fieldModifier,
        )
    } else {
        OutlinedTextField(
            state = fieldState,
            label = { Text(label) },
            readOnly = onValueChange == null,
            isError = presentation.error != null,
            lineLimits = if (presentation.singleLine) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine()
            },
            modifier = fieldModifier,
        )
    }
}

@Composable
internal fun SettingsPickerField(
    label: String,
    value: String,
    tag: String,
    presentation: SettingsPickerPresentation = SettingsPickerPresentation(),
    actions: SettingsPickerActions = SettingsPickerActions(),
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val fieldPresentation = SettingsFieldPresentation(error = presentation.error)
        if (presentation.compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTextField(
                    label = label,
                    value = value,
                    tag = tag,
                    presentation = fieldPresentation,
                    onValueChange = actions.onValueChange,
                )
                BrowseButton(tag = tag, actions = actions)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SettingsTextField(
                        label = label,
                        value = value,
                        tag = tag,
                        presentation = fieldPresentation,
                        onValueChange = actions.onValueChange,
                    )
                }
                BrowseButton(tag = tag, actions = actions)
            }
        }
        SettingsFieldError(presentation.error, "$tag-error")
    }
}

@Composable
private fun BrowseButton(
    tag: String,
    actions: SettingsPickerActions,
) {
    Button(onClick = actions.onBrowse, modifier = Modifier.testTag("$tag-browse")) {
        Text(actions.browseLabel)
    }
}

@Composable
internal fun SettingsFieldError(error: String?, tag: String) {
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(start = 16.dp).testTag(tag),
        )
    }
}

private val RejectAllInput = InputTransformation { revertAllChanges() }

private fun Modifier.secureTextSemantics(value: String, password: Boolean): Modifier {
    if (!password) return this
    val maskedText = AnnotatedString(if (value.isEmpty()) "" else MASKED_TOKEN)
    return semantics {
        inputText = maskedText
        editableText = maskedText
        isSensitiveData = true
    }
}
