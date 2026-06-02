package com.github.karlsabo.system

import io.github.oshai.kotlinlogging.KLogger

inline fun <T> runDesktopAppBootstrap(
    logger: KLogger,
    description: String,
    load: () -> T,
    buildErrorMessage: (Throwable) -> String,
): DesktopAppBootstrapResult<T> {
    logger.info { "Loading $description" }
    return runCatching {
        val loadedValue = load()
        logger.info { "$description loaded" }
        DesktopAppBootstrapResult.Loaded(loadedValue)
    }.getOrElse { error ->
        logger.error(error) { "Error loading $description" }
        DesktopAppBootstrapResult.Failed(buildErrorMessage(error))
    }
}
