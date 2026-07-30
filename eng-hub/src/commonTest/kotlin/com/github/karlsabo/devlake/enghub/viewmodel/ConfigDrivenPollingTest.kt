package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigDrivenPollingTest {
    @Test
    fun committedIntervalStopsObsoletePollingAndStartsReplacementPolling() = runTest {
        val configs = MutableStateFlow(EngHubConfig(pollIntervalMs = 600_000))
        val polls = mutableListOf<Pair<Long, Long>>()

        backgroundScope.launch {
            configDrivenPollingFlow(configs) { config ->
                fixedIntervalPollingFlow(config) {
                    testScheduler.currentTime to config.pollIntervalMs
                }
            }.collect { polls += it }
        }
        runCurrent()

        advanceTimeBy(1_000.milliseconds)
        configs.value = configs.value.copy(pollIntervalMs = 300_000)
        runCurrent()
        advanceTimeBy(300_000.milliseconds)
        runCurrent()
        advanceTimeBy(299_000.milliseconds)
        runCurrent()

        assertEquals(
            listOf(
                0L to 600_000L,
                1_000L to 300_000L,
                301_000L to 300_000L,
            ),
            polls,
        )
    }
}
