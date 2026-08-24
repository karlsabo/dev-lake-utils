package com.github.karlsabo.devlake.enghub

interface FilePicker {
    suspend fun pickFilePath(title: String): String?
}

expect fun createFilePicker(): FilePicker
