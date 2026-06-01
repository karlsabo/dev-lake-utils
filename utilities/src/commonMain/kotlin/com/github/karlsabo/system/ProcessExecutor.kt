package com.github.karlsabo.system

expect fun executeCommand(command: List<String>, workingDirectory: String? = null): ProcessResult
