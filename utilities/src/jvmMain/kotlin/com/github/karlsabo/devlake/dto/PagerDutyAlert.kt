package com.github.karlsabo.devlake.dto

import kotlinx.serialization.Serializable

@Serializable
data class PagerDutyAlert(val key: String, val description: String, val url: String)
