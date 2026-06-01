package com.github.karlsabo.devlake.enghub.screen

internal enum class EngHubPane(
    val label: String,
    val icon: String,
) {
    PullRequests("Pull Requests", "PR"),
    Notifications("Notifications", "🔔"),
    Worktrees("Worktrees", "🌳"),
}
