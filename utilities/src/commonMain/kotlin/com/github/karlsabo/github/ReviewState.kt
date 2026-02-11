package com.github.karlsabo.github

enum class ReviewStateValue {
    APPROVED,
    CHANGES_REQUESTED,
    COMMENTED,
    PENDING,
    DISMISSED;

    /**
     * Converts this review state to the string expected by the GitHub "create review" API.
     * The submit API uses present-tense verbs (APPROVE, REQUEST_CHANGES, COMMENT)
     * while the fetch API returns past-tense (APPROVED, CHANGES_REQUESTED, COMMENTED).
     */
    fun toSubmitEventString(): String = when (this) {
        APPROVED -> "APPROVE"
        CHANGES_REQUESTED -> "REQUEST_CHANGES"
        COMMENTED -> "COMMENT"
        PENDING -> "COMMENT"
        DISMISSED -> "COMMENT"
    }
}

data class ReviewState(
    val user: String,
    val state: ReviewStateValue,
)
