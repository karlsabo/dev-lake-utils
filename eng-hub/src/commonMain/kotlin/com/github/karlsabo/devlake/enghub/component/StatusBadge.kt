package com.github.karlsabo.devlake.enghub.component

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

private const val PASSED_STATUS_COLOR = 0xFF2DA44E
private const val FAILED_STATUS_COLOR = 0xFFCF222E
private const val RUNNING_STATUS_COLOR = 0xFFBF8700
private const val PENDING_STATUS_COLOR = 0xFF6E7781

@Composable
fun StatusBadge(status: CiStatus, modifier: Modifier = Modifier) {
    val (backgroundColor, label) = when (status) {
        CiStatus.PASSED -> Color(PASSED_STATUS_COLOR) to "Passed"
        CiStatus.FAILED -> Color(FAILED_STATUS_COLOR) to "Failed"
        CiStatus.RUNNING -> Color(RUNNING_STATUS_COLOR) to "Running"
        CiStatus.PENDING -> Color(PENDING_STATUS_COLOR) to "Pending"
    }

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp)
    }
}
