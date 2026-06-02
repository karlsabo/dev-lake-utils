package com.github.karlsabo.system

sealed interface DesktopAppBootstrapResult<out T> {
    data class Loaded<T>(
        val value: T,
    ) : DesktopAppBootstrapResult<T>

    data class Failed(
        val errorMessage: String,
    ) : DesktopAppBootstrapResult<Nothing>
}
