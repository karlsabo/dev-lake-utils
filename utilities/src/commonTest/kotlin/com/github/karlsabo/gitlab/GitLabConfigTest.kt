package com.github.karlsabo.gitlab

import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import com.github.karlsabo.gitlab.config.GitLabSecret
import com.github.karlsabo.gitlab.config.LoadedGitLabConfig
import com.github.karlsabo.gitlab.config.loadGitLabSecretIfPresent
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabConfigTest {
    @Test
    fun acceptsHttpsInstanceUrlsAndRemovesATrailingSlash() {
        assertEquals(
            "https://gitlab.internal.example",
            normalizeGitLabInstanceUrl("https://gitlab.internal.example/"),
        )
        assertEquals(
            "https://gitlab.internal.example/gitlab",
            normalizeGitLabInstanceUrl(" https://gitlab.internal.example/gitlab/ "),
        )
        assertTrue(isValidGitLabInstanceUrl("https://gitlab.internal.example"))
    }

    @Test
    fun rejectsBlankUnencryptedCredentialedAndQueryUrls() {
        listOf(
            "",
            "   ",
            "http://gitlab.internal.example",
            "https://",
            "https://user:token@gitlab.internal.example",
            "https://gitlab.internal.example?query=1",
            "https://gitlab.internal.example#fragment",
            "not a url",
        ).forEach { candidate ->
            assertNull(normalizeGitLabInstanceUrl(candidate), "Expected $candidate to be rejected")
            assertFalse(isValidGitLabInstanceUrl(candidate))
        }
    }

    @Test
    fun loadedConfigIsReadyOnlyWithAValidUrlASecretPathAndAToken() {
        val ready = LoadedGitLabConfig("https://gitlab.internal.example/", "/secrets/gitlab.json", GitLabSecret("pat"))
        assertEquals("https://gitlab.internal.example", ready.toApiRestConfig()?.instanceUrl)
        assertTrue(ready.isAccessReady())

        assertFalse(ready.copy(secret = GitLabSecret("")).isAccessReady())
        assertFalse(ready.copy(tokenPath = "").isAccessReady())
        assertFalse(ready.copy(instanceUrl = "http://gitlab.internal.example").isAccessReady())
    }

    @Test
    fun secretsAndRestConfigsNeverExposeTheTokenThroughToString() {
        val token = "glpat-synthetic-secret"
        assertFalse(token in GitLabSecret(token).toString())
        assertFalse(token in GitLabApiRestConfig("https://gitlab.internal.example", token).toString())
        val loadedConfig =
            LoadedGitLabConfig("https://gitlab.internal.example", "/secret.json", GitLabSecret(token))
        assertFalse(token in loadedConfig.toString())
    }

    @Test
    fun loadsTheSecretFromItsOwnFileOnly() {
        val directory = Path(SystemTemporaryDirectory, "gitlab-config-${Random.nextLong()}")
        val secretPath = Path(directory, "gitlab-secret.json")
        SystemFileSystem.createDirectories(directory)
        try {
            writeText(secretPath, lenientJson.encodeToString(GitLabSecret.serializer(), GitLabSecret("pat")))

            assertEquals(GitLabSecret("pat"), loadGitLabSecretIfPresent(secretPath))

            writeText(secretPath, "invalid")
            assertNull(loadGitLabSecretIfPresent(secretPath))
            assertNull(loadGitLabSecretIfPresent(Path(directory, "missing.json")))
        } finally {
            SystemFileSystem.delete(secretPath, mustExist = false)
            SystemFileSystem.delete(directory, mustExist = false)
        }
    }
}

private fun writeText(path: Path, text: String) {
    SystemFileSystem.sink(path, false).buffered().use { sink -> sink.writeString(text) }
}
