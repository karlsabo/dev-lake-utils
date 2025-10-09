package com.github.karlsabo.github.notification

import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.loadGitHubConfig
import com.github.karlsabo.tools.gitHubConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.io.files.Path


/**
 * Demo that lists and cleans up notifications for the authenticated GitHub user.
 */
fun main(args: Array<String>) {
    println("GitHub Notifications Demo")

    val configPath = Path(gitHubConfigPath)

    try {
        println("Loading GitHub configuration from $configPath")
        val config = loadGitHubConfig(configPath)
        val githubApi = GitHubRestApi(config)

        runBlocking {
            val notifications = githubApi.listNotifications()
            println("Found ${notifications.size} notifications")

            if (notifications.isEmpty()) {
                println("No notifications to show.")
                return@runBlocking
            }

            notifications.forEachIndexed { index, n ->
                println("\n#${index + 1}")
                println("  Thread ID: ${n.id}")
                println("  Repository: ${n.repository.fullName}")
                println("  Subject: ${n.subject.title} (${n.subject.type})")
                println("  Reason: ${n.reason}")
                println("  Unread: ${n.unread}")
                println("  Updated: ${formatDate(n.updatedAt)}")
                n.lastReadAt?.let { println("  Last read: ${formatDate(it)}") }

                if (n.subject.type.equals("PullRequest", ignoreCase = true)) {
                    val prUrl = n.subject.url
                    if (prUrl != null) {
                        try {
                            val pr = githubApi.getPullRequestByUrl(prUrl)
                            val status = when {
                                pr.mergedAt != null -> "merged"
                                pr.state?.equals("closed", ignoreCase = true) == true -> "closed"
                                else -> pr.state ?: "unknown"
                            }
                            println("  PR Status: $status")
                            pr.htmlUrl?.let { println("  PR URL: $it") }

                            // Auto-approve matching PRs only if not already approved; always mark notification done
                            val title = n.subject.title
                            val matchesDemoAppFile =
                                title.startsWith("Updating appfile", ignoreCase = true)
                                        && (title.contains("demo", ignoreCase = true)
                                        || title.contains("to dev", ignoreCase = true))
                            if (matchesDemoAppFile
                                && pr.mergedAt == null
                                && pr.state?.equals("closed", ignoreCase = true) != true
                            ) {
                                try {
                                    val alreadyApproved = githubApi.hasAnyApprovedReview(prUrl)
                                    if (alreadyApproved) {
                                        println("  Action: PR already approved; skipping additional approval ${pr.url} ${n.subject.title}")
                                    } else {
                                        githubApi.approvePullRequestByUrl(
                                            prUrl,
                                            body = "Auto-approving demo appfile update"
                                        )
                                        println("  Action: Approved PR based on title match ${pr.url} ${n.subject.title}")
                                    }
                                    try {
                                        githubApi.markNotificationAsDone(n.id)
                                        println("  Action: Marked notification as done")
                                    } catch (e: Exception) {
                                        println("  Action: Failed to mark notification as done (${e.message})")
                                    }
                                } catch (e: Exception) {
                                    println("  Action: Failed to check/approve PR ${pr.url} ${n.subject.title} (${e.message})")
                                }
                            }

                            if (pr.mergedAt != null || pr.state?.equals("closed", ignoreCase = true) == true) {
                                try {
                                    githubApi.markNotificationAsDone(n.id)
                                    println("  Action: Marked notification as done")
                                } catch (e: Exception) {
                                    println("  Action: Failed to mark notification as done (${e.message})")
                                }
                            }
                        } catch (e: Exception) {
                            println("  PR Status: failed to fetch (${e.message})")
                        }
                    } else {
                        println("  PR Status: unavailable (no subject URL)")
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

private fun formatDate(instant: Instant): String {
    return instant.toString().replace('T', ' ').removeSuffix("Z")
}
