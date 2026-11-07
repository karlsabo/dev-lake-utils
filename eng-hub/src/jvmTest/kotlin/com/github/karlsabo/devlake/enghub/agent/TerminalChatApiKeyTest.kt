package com.github.karlsabo.devlake.enghub.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalChatApiKeyTest {
    @Test
    fun `loads and trims an API key file`() {
        val path = Files.createTempFile("eng-hub-api-key-", ".txt")
        try {
            Files.writeString(path, "  synthetic-token\n")

            assertEquals("synthetic-token", loadLmStudioApiKey(path.toString()))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `rejects an empty API key file`() {
        val path = Files.createTempFile("eng-hub-api-key-", ".txt")
        try {
            Files.writeString(path, " \n")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    loadLmStudioApiKey(path.toString())
                }
            assertTrue(failure.message.orEmpty().contains("must not be empty"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `rejects an unreadable API key file`() {
        val missingPath = Files.createTempDirectory("eng-hub-api-key-").resolve("missing-key")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                loadLmStudioApiKey(missingPath.toString())
            }

        assertTrue(failure.message.orEmpty().contains("Cannot read LM Studio API key file"))
        Files.deleteIfExists(missingPath.parent)
    }
}
