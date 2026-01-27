package com.github.karlsabo.system

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.uname
import platform.posix.utsname

actual fun getEnv(name: String): String? {
    val envValue = getenv(name) ?: return null
    return envValue.toKString()
}

actual fun osName(): String {
    return memScoped {
        val utsname = alloc<utsname>()
        uname(utsname.ptr)
        utsname.sysname.toKString()
    }
}
