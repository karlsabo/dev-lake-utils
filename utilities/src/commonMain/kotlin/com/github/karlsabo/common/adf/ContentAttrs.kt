package com.github.karlsabo.common.adf

import kotlinx.serialization.Serializable

/**
 * Represents the attributes of a ContentNode in the Atlassian Document Format (ADF).
 */
@Serializable
data class ContentAttrs(
    val id: String? = null,
    val text: String? = null,
    val accessLevel: String? = null,
    val shortName: String? = null,
)
