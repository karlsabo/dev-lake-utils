package com.github.karlsabo.devlake.enghub.viewmodel

import java.io.IOException

internal actual fun Throwable.isRetriablePollingFailure(): Boolean = this is IOException
