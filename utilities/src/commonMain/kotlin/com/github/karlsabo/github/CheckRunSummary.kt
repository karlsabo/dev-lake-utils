package com.github.karlsabo.github

data class CheckRunSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val inProgress: Int,
    val status: CiStatus,
)
