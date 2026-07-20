package com.github.karlsabo.github

enum class ReviewStateValue {
    APPROVED,
    CHANGES_REQUESTED,
    COMMENTED,
    PENDING,
    DISMISSED,
}

data class ReviewState(
    val user: String,
    val state: ReviewStateValue,
)
