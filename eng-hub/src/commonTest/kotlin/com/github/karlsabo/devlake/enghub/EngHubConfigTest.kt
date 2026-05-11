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
            worktreePollIntervalMs = 120_000,
            repositoriesBaseDir = "/tmp/example/repos",
            gitHubAuthor = "example-author",
            planningMarkdownDir = "/tmp/example/llm-planning",
            localRepositories = listOf(
                LocalRepositoryConfig(
                    path = "/tmp/example/repos/example-web",
                    setupCommands = listOf(
                        "direnv allow",
                        "idea ./",
                    ),
                ),
                LocalRepositoryConfig(path = "/tmp/example/repos/example-worker"),
            ),
            worktreeSetupCommands = mapOf(
                "/tmp/example/repos/example-web" to listOf(
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
        assertTrue(json.contains("\"worktreePollIntervalMs\""))
    }

    @Test
    fun deserializesUnifiedLocalRepositoryObjects() {
        val json = """
            {
              "localRepositories": [
                {
                  "path": "/tmp/example/repos/example-web",
                  "setupCommands": [
                    "direnv allow",
                    "idea ./"
                  ]
                },
                {
                  "path": "/tmp/example/repos/example-worker"
                }
              ]
            }
        """.trimIndent()

        val decoded = lenientJson.decodeFromString(EngHubConfig.serializer(), json)

        assertEquals(
            listOf(
                LocalRepositoryConfig(
                    path = "/tmp/example/repos/example-web",
                    setupCommands = listOf(
                        "direnv allow",
                        "idea ./",
                    ),
                ),
                LocalRepositoryConfig(path = "/tmp/example/repos/example-worker"),
            ),
            decoded.localRepositories,
        )
    }

    @Test
    fun deserializesLegacyLocalRepositoryStringsAsEntries() {
        val json = """
            {
              "localRepositories": [
                "/tmp/example/repos/example-web",
                "/tmp/example/repos/example-worker"
              ]
            }
        """.trimIndent()

        val decoded = lenientJson.decodeFromString(EngHubConfig.serializer(), json)

        assertEquals(
            listOf(
                LocalRepositoryConfig(path = "/tmp/example/repos/example-web"),
                LocalRepositoryConfig(path = "/tmp/example/repos/example-worker"),
            ),
            decoded.localRepositories,
        )
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
        assertEquals(120_000, decoded.worktreePollIntervalMs)
    }

    @Test
    fun usesEngHubConfigFileName() {
        assertTrue(engHubConfigPath.toString().endsWith("eng-hub-config.json"))
    }
}
