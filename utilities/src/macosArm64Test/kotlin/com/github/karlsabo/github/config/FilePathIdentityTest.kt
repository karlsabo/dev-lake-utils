package com.github.karlsabo.github.config

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.symlink
import platform.posix.unlink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FilePathIdentityTest {
    @Test
    fun rejectsSymlinkLoopsInsteadOfTreatingThemAsMissingPaths() {
        val directory = Path(SystemTemporaryDirectory, "file-path-identity-${Random.nextLong()}")
        val loop = Path(directory, "loop")
        SystemFileSystem.createDirectories(directory)

        try {
            assertEquals(0, symlink("loop", loop.toString()))
            val error = assertFailsWith<IllegalArgumentException> {
                platformResolvedFilePath(Path(loop, "child"))
            }

            assertEquals("Could not inspect file path", error.message)
        } finally {
            unlink(loop.toString())
            SystemFileSystem.delete(directory, mustExist = false)
        }
    }
}
