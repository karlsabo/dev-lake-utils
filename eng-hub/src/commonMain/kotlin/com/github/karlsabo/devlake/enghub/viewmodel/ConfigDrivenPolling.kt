@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

internal fun <T> configDrivenPollingFlow(
    configs: Flow<EngHubConfig>,
    pollingFlow: (EngHubConfig) -> Flow<T>,
): Flow<T> = configs.flatMapLatest(pollingFlow)

internal fun <T> fixedIntervalPollingFlow(
    config: EngHubConfig,
    poll: suspend () -> T,
): Flow<T> = flow {
    while (true) {
        emit(poll())
        delay(config.pollIntervalMs.milliseconds)
    }
}
