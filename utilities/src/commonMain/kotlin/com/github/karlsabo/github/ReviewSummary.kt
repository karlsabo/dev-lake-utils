package com.github.karlsabo.github

data class ReviewSummary(
    val approvedCount: Int,
    val requestedCount: Int,
    val reviews: List<ReviewState>,
)
