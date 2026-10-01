package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.PullRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingGitHubApiTest {
    @Test
    fun concurrentPullRequestCallsAreAllRecorded() = runBlocking {
        val urls = List(100) { "https://api.github.com/repos/owner/repository/pulls/$it" }
        val api = RecordingGitHubApi(urls.associateWith { PullRequest(number = 1) })
        val start = CompletableDeferred<Unit>()

        coroutineScope {
            urls.forEach { url ->
                launch(Dispatchers.Default) {
                    start.await()
                    api.getPullRequestByUrl(url)
                }
            }
            start.complete(Unit)
        }

        assertEquals(urls.size, api.pullRequestByUrlCalls.size)
        assertEquals(urls.toSet(), api.pullRequestByUrlCalls.toSet())
    }
}
