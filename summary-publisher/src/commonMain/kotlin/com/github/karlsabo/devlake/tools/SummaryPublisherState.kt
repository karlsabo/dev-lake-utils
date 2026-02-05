package com.github.karlsabo.devlake.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.jira.config.JiraApiRestConfig
import com.github.karlsabo.pagerduty.PagerDutyApiRestConfig
import com.github.karlsabo.text.TextSummarizerOpenAiConfigNoSecrets
import com.github.karlsabo.tools.model.ProjectSummary

data class ProjectSummaryHolder(val projectSummary: ProjectSummary, val message: String)

class SummaryPublisherState {
    var isConfigLoaded by mutableStateOf(false)
    var isLoadingSummary by mutableStateOf(true)
    var isSendingSlackMessage by mutableStateOf(false)
    var isDisplayErrorDialog by mutableStateOf(false)

    var errorMessage by mutableStateOf("")
    var topLevelSummary by mutableStateOf("Loading summary")
    var publishButtonText by mutableStateOf("Publish to Slack")
    var publishButtonEnabled by mutableStateOf(true)

    var projectSummaries by mutableStateOf<List<ProjectSummaryHolder>>(emptyList())

    var summaryConfig by mutableStateOf(SummaryPublisherConfig())
    var textSummarizerConfig by mutableStateOf<TextSummarizerOpenAiConfigNoSecrets?>(null)
    var jiraConfig by mutableStateOf<JiraApiRestConfig?>(null)
    var gitHubConfig by mutableStateOf<GitHubApiRestConfig?>(null)
    var pagerDutyConfig by mutableStateOf<PagerDutyApiRestConfig?>(null)
    var usersConfig by mutableStateOf(UsersConfig(emptyList()))

    fun updateProjectMessage(index: Int, message: String) {
        projectSummaries = projectSummaries.toMutableList().also {
            it[index] = it[index].copy(message = message)
        }
    }

    fun removeProject(index: Int) {
        projectSummaries = projectSummaries.toMutableList().also {
            it.removeAt(index)
        }
    }

    fun onPublishStarted() {
        publishButtonEnabled = false
        isSendingSlackMessage = true
    }

    fun onPublishCompleted(success: Boolean) {
        isSendingSlackMessage = false
        publishButtonText = if (success) "Message sent!" else "Failed to send message"
    }
}

@Composable
fun rememberSummaryPublisherState() = remember { SummaryPublisherState() }
