package com.github.karlsabo.devlake.ghpanel

import com.github.karlsabo.tools.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubControlPanelConfigTest {

    @Test
    fun serializesAndDeserializesNewSetupFields() {
        val config = GitHubControlPanelConfig(
            organizationIds = listOf("example-org"),
            pollIntervalMs = 600_000,
            repositoriesBaseDir = "/tmp/example/repos",
            gitHubAuthor = "example-author",
            worktreeSetupCommands = mapOf(
                "/tmp/example/repos/app" to listOf(
                    "direnv allow",
                    "idea ./"
                )
            ),
            setupShell = "/bin/zsh",
        )

        val json = lenientJson.encodeToString(GitHubControlPanelConfig.serializer(), config)
        val decoded = lenientJson.decodeFromString(GitHubControlPanelConfig.serializer(), json)

        assertEquals(config, decoded)
        assertTrue(json.contains("\"worktreeSetupCommands\""))
        assertTrue(json.contains("\"setupShell\""))
    }

    @Test
    fun missingSetupFieldsUseDefaults() {
        val legacyJson = """
            {
              "organizationIds": ["example-org"],
              "pollIntervalMs": 600000,
              "repositoriesBaseDir": "/tmp/example/repos",
              "gitHubAuthor": "example-author"
            }
        """.trimIndent()

        val decoded = lenientJson.decodeFromString(GitHubControlPanelConfig.serializer(), legacyJson)

        assertEquals(emptyMap(), decoded.worktreeSetupCommands)
        assertEquals("/bin/zsh", decoded.setupShell)
    }
}
