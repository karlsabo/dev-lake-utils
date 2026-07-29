package com.github.karlsabo.devlake.enghub.screen

internal enum class EngHubPane(
    val label: String,
    val icon: String,
) {
    PullRequests("Pull Requests", "⛙"),
    Notifications("Notifications", "🔔"),
    Worktrees("Worktrees", "🌳"),
    Settings("Settings", "⚙"),
}
