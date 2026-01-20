package com.github.karlsabo.jira

data class CustomFieldFilter(
    val fieldId: String,
    val values: List<String>,
)
