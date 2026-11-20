package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val CardShape = RoundedCornerShape(8.dp)
private val PaneShape = RoundedCornerShape(6.dp)

@Composable
internal fun settingsBorderColor(): Color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)

/** A titled, bordered group that separates one cluster of related settings from its neighbors. */
@Composable
internal fun SettingsCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        elevation = 0.dp,
        border = BorderStroke(1.dp, settingsBorderColor()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            title?.let { SettingsCardTitle(it) }
            content()
        }
    }
}

@Composable
internal fun SettingsCardTitle(title: String) {
    Text(title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
}

/** A bordered region inside a card, such as the repository list or the selected repository's details. */
@Composable
internal fun SettingsPane(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = PaneShape,
        border = BorderStroke(1.dp, settingsBorderColor()),
        color = MaterialTheme.colors.surface,
        content = content,
    )
}
