@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.Duration.Companion.milliseconds

internal fun <T> configDrivenPollingFlow(
    configs: Flow<EngHubConfig>,
    pollingFlow: (EngHubConfig) -> Flow<T>,
): Flow<T> = configs.flatMapLatest(pollingFlow)

internal fun <T> Flow<T>.clearBeforeFirstEmission(): Flow<T?> = map<T, T?> { value -> value }.onStart { emit(null) }

internal fun <T> fixedIntervalPollingFlow(
    config: EngHubConfig,
    poll: suspend () -> T,
): Flow<T> = flow {
    while (true) {
        emit(poll())
        delay(config.pollIntervalMs.milliseconds)
    }
}

internal fun <T> worktreePollingFlow(
    configs: Flow<EngHubConfig>,
    poll: suspend () -> T,
): Flow<T> = configs
    .map { config -> config.worktreePollIntervalMs.coerceAtLeast(1) }
    .distinctUntilChanged()
    .flatMapLatest { intervalMs ->
        flow {
            while (true) {
                delay(intervalMs.milliseconds)
                emit(poll())
            }
        }
    }
