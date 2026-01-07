package com.github.karlsabo.linear

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowState(
    val id: String,
    val name: String? = null,
    val type: String? = null,
)
