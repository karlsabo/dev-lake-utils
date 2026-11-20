package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Width reserved beside scrollable settings content so the scrollbar never covers a control. */
internal val SettingsScrollbarGutter = 14.dp

/** A settings scrollbar whose thumb stays visible without hovering, so scrollable regions are discoverable. */
@Composable
internal fun SettingsVerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier,
        style = ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 300,
            unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
        ),
    )
}
