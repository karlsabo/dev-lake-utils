package com.github.karlsabo.git

data class Worktree(
    val path: String,
    val branch: String,
    val commitHash: String,
)
