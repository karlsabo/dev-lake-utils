package com.github.karlsabo.projectmanagement

import kotlinx.serialization.Serializable

/**
 * Represents the high-level category of an issue's status.
 */
@Serializable
enum class StatusCategory {
    TODO,
    IN_PROGRESS,
    DONE,
}
