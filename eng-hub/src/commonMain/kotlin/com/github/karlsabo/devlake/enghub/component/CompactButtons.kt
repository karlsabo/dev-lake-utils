package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/** Secondary action that stays visually quieter than the filled primary action beside it. */
@Composable
internal fun SecondaryButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = CompactButtonPadding,
        modifier = Modifier.testTag(tag),
    ) {
        Text(text)
    }
}

/** Outlined error-colored action for removals, so destructive actions never look like the primary action. */
@Composable
internal fun DestructiveButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = CompactButtonPadding,
        border = BorderStroke(1.dp, MaterialTheme.colors.error.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colors.error),
        modifier = Modifier.testTag(tag),
    ) {
        Text(text)
    }
}
