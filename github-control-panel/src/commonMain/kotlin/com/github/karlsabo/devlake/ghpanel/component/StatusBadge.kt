package com.github.karlsabo.devlake.ghpanel.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.karlsabo.github.CiStatus

@Composable
fun StatusBadge(status: CiStatus, modifier: Modifier = Modifier) {
    val (backgroundColor, label) = when (status) {
        CiStatus.PASSED -> Color(0xFF2DA44E) to "Passed"
        CiStatus.FAILED -> Color(0xFFCF222E) to "Failed"
        CiStatus.RUNNING -> Color(0xFFBF8700) to "Running"
        CiStatus.PENDING -> Color(0xFF6E7781) to "Pending"
    }

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp)
    }
}
