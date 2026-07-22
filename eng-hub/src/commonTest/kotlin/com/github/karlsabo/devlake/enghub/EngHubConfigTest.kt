package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import com.github.karlsabo.tools.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            setupShell = "/bin/zsh",
        )

        val json = lenientJson.encodeToString(EngHubConfig.serializer(), config)
        val decoded = lenientJson.decodeFromString(EngHubConfig.serializer(), json)

        assertEquals(config, decoded)
        assertFalse(json.contains("\"worktreeSetupCommands\""))
        assertFalse(json.contains("\"setupCommands\":[]"))
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
    fun omitsEmptySetupCommandsWhenSerializing() {
        val config = EngHubConfig(
            localRepositories = listOf(
                LocalRepositoryConfig(
                    path = "/workspace/example-service",
                    setupCommands = listOf("direnv allow"),
                ),
            ),
        )

        val json = lenientJson.encodeToString(EngHubConfig.serializer(), config)

        assertTrue(json.contains("\"localRepositories\""))
        assertFalse(json.contains("\"worktreeSetupCommands\""))
        assertFalse(json.contains("\"setupCommands\":[]"))
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
        val expectedSetupShell = if (osFamily() == OsFamily.WINDOWS) "powershell.exe" else "/bin/zsh"

        assertEquals("powershell.exe", defaultSetupShell(OsFamily.WINDOWS))
        assertEquals(expectedSetupShell, EngHubConfig().setupShell)
        assertEquals(expectedSetupShell, decoded.setupShell)
        assertEquals("", decoded.planningMarkdownDir)
        assertEquals(emptyList(), decoded.localRepositories)
        assertEquals(120_000, decoded.worktreePollIntervalMs)
    }

    @Test
    fun usesEngHubConfigFileName() {
        assertTrue(engHubConfigPath.toString().endsWith("eng-hub-config.json"))
    }
}
