package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Cancels independent background jobs at disposal without waiting for discovery's blocking children. */
internal fun ViewModel.localRepositoryBackgroundScope(): CoroutineScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    addCloseable { scope.cancel() }
    return scope
}
