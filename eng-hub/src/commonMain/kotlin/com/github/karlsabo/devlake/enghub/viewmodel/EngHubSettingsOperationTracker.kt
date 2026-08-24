package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal class EngHubSettingsOperationTracker(
    private val coroutineScope: CoroutineScope,
) {
    private val activeJobs = MutableStateFlow<Set<Job>>(emptySet())
    private var operationTail: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        val predecessor = operationTail
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            predecessor?.join()
            block()
        }
        operationTail = job
        activeJobs.update { jobs -> jobs + job }
        job.invokeOnCompletion {
            activeJobs.update { jobs -> jobs - job }
            if (operationTail === job) operationTail = null
        }
        job.start()
        return job
    }

    suspend fun awaitIdle() {
        while (activeJobs.value.isNotEmpty()) {
            activeJobs.value.toList().joinAll()
        }
    }
}
