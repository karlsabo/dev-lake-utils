package com.github.karlsabo.devlake.enghub

interface DirectoryPicker {
    suspend fun pickDirectory(title: String): String?
}

expect fun createDirectoryPicker(): DirectoryPicker
