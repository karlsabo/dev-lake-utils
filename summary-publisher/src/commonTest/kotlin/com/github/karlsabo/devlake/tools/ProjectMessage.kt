@file:OptIn(ExperimentalUuidApi::class)

package com.github.karlsabo.devlake.tools

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ProjectMessage(
    val id: Uuid,
    val text: String,
)
