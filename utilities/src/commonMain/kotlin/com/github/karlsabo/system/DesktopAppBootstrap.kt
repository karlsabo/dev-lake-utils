package com.github.karlsabo.system

import io.github.oshai.kotlinlogging.KLogger

sealed interface DesktopAppBootstrapResult<out T> {
    data class Loaded<T>(val value: T) : DesktopAppBootstrapResult<T>
    data class Failed(val errorMessage: String) : DesktopAppBootstrapResult<Nothing>
}

inline fun <T> runDesktopAppBootstrap(
    logger: KLogger,
    description: String,
    load: () -> T,
    buildErrorMessage: (Exception) -> String,
): DesktopAppBootstrapResult<T> {
    logger.info { "Loading $description" }
    return try {
        val loadedValue = load()
        logger.info { "$description loaded" }
        DesktopAppBootstrapResult.Loaded(loadedValue)
    } catch (error: Exception) {
        logger.error(error) { "Error loading $description" }
        DesktopAppBootstrapResult.Failed(buildErrorMessage(error))
    }
}
