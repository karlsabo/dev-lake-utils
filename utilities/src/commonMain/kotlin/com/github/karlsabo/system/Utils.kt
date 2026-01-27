package com.github.karlsabo.system

/**
 * Operating system family.
 */
enum class OsFamily {
    MACOS,
    WINDOWS,
    LINUX,
    UNKNOWN
}

/**
 * Gets the value of an environment variable.
 *
 * @param name The name of the environment variable.
 * @return The value of the environment variable, or null if it doesn't exist.
 */
expect fun getEnv(name: String): String?

/**
 * Returns the operating system family.
 */
expect fun osFamily(): OsFamily
