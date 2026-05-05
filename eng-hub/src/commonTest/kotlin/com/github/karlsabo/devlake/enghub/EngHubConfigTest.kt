package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.tools.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngHubConfigTest {

    @Test
    fun serializesAndDeserializesNewSetupFields() {
        val config = EngHubConfig(
            organizationIds = listOf("example-org"),
            pollIntervalMs = 600_000,
            repositoriesBaseDir = "/tmp/example/repos",
            gitHubAuthor = "example-author",
            planningMarkdownDir = "/tmp/example/llm-planning",
            localRepositories = listOf(
                "/tmp/example/repos/app",
                "/tmp/example/repos/fender",
            ),
            worktreeSetupCommands = mapOf(
                "/tmp/example/repos/app" to listOf(
                    "direnv allow",
                    "idea ./"
                )
            ),
            setupShell = "/bin/zsh",
        )

        val json = lenientJson.encodeToString(EngHubConfig.serializer(), config)
        val decoded = lenientJson.decodeFromString(EngHubConfig.serializer(), json)

        assertEquals(config, decoded)
        assertTrue(json.contains("\"worktreeSetupCommands\""))
        assertTrue(json.contains("\"setupShell\""))
        assertTrue(json.contains("\"planningMarkdownDir\""))
        assertTrue(json.contains("\"localRepositories\""))
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

        val decoded = lenientJson.decodeFromString(EngHubConfig.serializer(), legacyJson)

        assertEquals(emptyMap(), decoded.worktreeSetupCommands)
        assertEquals("/bin/zsh", decoded.setupShell)
        assertEquals("", decoded.planningMarkdownDir)
        assertEquals(emptyList(), decoded.localRepositories)
    }

    @Test
    fun usesEngHubConfigFileName() {
        assertTrue(engHubConfigPath.toString().endsWith("eng-hub-config.json"))
    }
}
