package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class EngHubConfigState(
    initialConfig: EngHubConfig,
    private val writer: EngHubConfigWriter,
) {
    private val mutableConfig = MutableStateFlow(initialConfig)
    private val updateMutex = Mutex()

    val current: EngHubConfig
        get() = mutableConfig.value
    val config: StateFlow<EngHubConfig> = mutableConfig.asStateFlow()

    suspend fun update(transform: (EngHubConfig) -> EngHubConfig): EngHubConfig = updateMutex.withLock {
        val currentConfig = mutableConfig.value
        val updatedConfig = transform(currentConfig)
        if (updatedConfig != currentConfig) {
            writer.save(updatedConfig)
            mutableConfig.value = updatedConfig
        }
        updatedConfig
    }
}
