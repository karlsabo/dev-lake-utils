package com.github.karlsabo.system

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
