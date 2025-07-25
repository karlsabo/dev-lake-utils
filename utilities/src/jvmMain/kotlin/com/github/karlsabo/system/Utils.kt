package com.github.karlsabo.system


actual fun getEnv(name: String): String? = System.getenv(name)
