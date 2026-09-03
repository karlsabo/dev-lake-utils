package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            llmTemplateValues = mapOf(
                "ALERT_TRIAGE_WHERE_TO_LOOK" to "- Incident management: inspect the active incident",
            ),
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
        assertTrue(json.contains("\"llmTemplateValues\""))
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
        assertEquals(emptyMap(), decoded.llmTemplateValues)
        assertEquals(emptyList(), decoded.localRepositories)
        assertEquals(120_000, decoded.worktreePollIntervalMs)
    }

    @Test
    fun invalidPrimaryLoadsBackupValuesForSettings() {
        val path = temporaryConfigPath()
        val backupPath = Path("$path.bak")
        val backupConfig = EngHubConfig(
            organizationIds = listOf("backup-org"),
            gitHubAuthor = "backup-author",
        )
        writeText(path, "not valid JSON")
        writeText(backupPath, lenientJson.encodeToString(EngHubConfig.serializer(), backupConfig))

        try {
            val recoveredConfig = assertNotNull(loadEngHubConfigIfPresent(path))
            val settings = createEngHubSettingsUiState(
                recoveredConfig,
                GitHubConfig(tokenPath = ""),
                GitHubSecret(githubToken = ""),
            )

            assertEquals(listOf("backup-org"), settings.organizationIds)
            assertEquals("backup-author", settings.gitHubAuthor)
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            SystemFileSystem.delete(backupPath, mustExist = false)
        }
    }

    @Test
    fun semanticallyInvalidPrimaryLoadsPersistenceValidBackup() {
        val path = temporaryConfigPath()
        val backupPath = Path("$path.bak")
        val backupConfig = EngHubConfig(gitHubAuthor = "backup-author")
        writeText(
            path,
            lenientJson.encodeToString(
                EngHubConfig.serializer(),
                EngHubConfig(pollIntervalMs = 500, localRepositories = listOf(LocalRepositoryConfig("/workspace/api"))),
            ),
        )
        writeText(backupPath, lenientJson.encodeToString(EngHubConfig.serializer(), backupConfig))

        try {
            assertEquals(backupConfig, loadEngHubConfigIfPresent(path))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            SystemFileSystem.delete(backupPath, mustExist = false)
        }
    }

    @Test
    fun repositoryPathIdentityResolvesLexicalAliasesAndPreservesEdgeRoots() {
        assertEquals("/workspace/api", "/WORKSPACE/./other/../API//".normalizedRepositoryPath(OsFamily.MACOS))
        assertEquals("/WORKSPACE/API", "/WORKSPACE/API".normalizedRepositoryPath(OsFamily.LINUX))
        assertEquals("../api", "child/../../api".normalizedRepositoryPath(OsFamily.LINUX))
        assertEquals("/", "/../.././".normalizedRepositoryPath(OsFamily.LINUX))
        assertEquals(".", "child/..".normalizedRepositoryPath(OsFamily.LINUX))
        assertEquals(
            "c:/workspace/api",
            "C:\\Workspace\\.\\other\\..\\API\\".normalizedRepositoryPath(OsFamily.WINDOWS),
        )
        assertEquals("c:api", "C:other\\..\\API".normalizedRepositoryPath(OsFamily.WINDOWS))
        assertEquals("c:/", "C:\\..\\".normalizedRepositoryPath(OsFamily.WINDOWS))
        assertEquals(
            "//server/share",
            "\\\\SERVER\\Share\\child\\..\\..".normalizedRepositoryPath(OsFamily.WINDOWS),
        )
    }

    @Test
    fun macOsLoadedConfigRejectsCaseAndLexicalRepositoryAliases() {
        val path = temporaryConfigPath()
        val config = EngHubConfig(
            localRepositories = listOf(
                LocalRepositoryConfig("/workspace/api"),
                LocalRepositoryConfig("/WORKSPACE/other/../API"),
            ),
        )
        writeText(path, lenientJson.encodeToString(EngHubConfig.serializer(), config))

        try {
            assertNull(decodeEngHubConfigIfValid(path, OsFamily.MACOS))
            assertEquals(config, decodeEngHubConfigIfValid(path, OsFamily.LINUX))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun invalidIntervalsAndCollectionsLoadAsAbsentWithoutBackup() {
        val invalidConfigs = listOf(
            EngHubConfig(pollIntervalMs = 0),
            EngHubConfig(worktreePollIntervalMs = -1_000),
            EngHubConfig(pollIntervalMs = 500),
            EngHubConfig(organizationIds = listOf("acme", " ACME ")),
            EngHubConfig(
                localRepositories = listOf(
                    LocalRepositoryConfig("/workspace/api"),
                    LocalRepositoryConfig("/workspace/api/"),
                ),
            ),
            EngHubConfig(
                localRepositories = listOf(
                    LocalRepositoryConfig("/workspace/api"),
                    LocalRepositoryConfig("/workspace/./api"),
                ),
            ),
            EngHubConfig(
                localRepositories = listOf(
                    LocalRepositoryConfig("/workspace/api"),
                    LocalRepositoryConfig("/workspace/other/../api"),
                ),
            ),
            EngHubConfig(
                localRepositories = listOf(
                    LocalRepositoryConfig("/"),
                    LocalRepositoryConfig("/workspace/../"),
                ),
            ),
            EngHubConfig(localRepositories = listOf(LocalRepositoryConfig("/workspace/api", listOf("")))),
        )

        invalidConfigs.forEach { config ->
            val path = temporaryConfigPath()
            writeText(path, lenientJson.encodeToString(EngHubConfig.serializer(), config))
            try {
                assertNull(loadEngHubConfigIfPresent(path), "Expected config to be rejected: $config")
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }
    }

    @Test
    fun unreadablePrimaryLoadsValidBackup() {
        val path = temporaryConfigPath()
        val backupPath = Path("$path.bak")
        val backupConfig = EngHubConfig(gitHubAuthor = "backup-author")
        SystemFileSystem.createDirectories(path)
        writeText(backupPath, lenientJson.encodeToString(EngHubConfig.serializer(), backupConfig))

        try {
            assertEquals(backupConfig, loadEngHubConfigIfPresent(path))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            SystemFileSystem.delete(backupPath, mustExist = false)
        }
    }

    @Test
    fun unreadablePrimaryWithoutUsableBackupLoadsAsAbsent() {
        val path = temporaryConfigPath()
        SystemFileSystem.createDirectories(path)

        try {
            assertNull(loadEngHubConfigIfPresent(path))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun invalidPrimaryAndBackupLoadAsAbsent() {
        val path = temporaryConfigPath()
        val backupPath = Path("$path.bak")
        writeText(path, "not valid JSON")
        writeText(backupPath, "also not valid JSON")

        try {
            assertNull(loadEngHubConfigIfPresent(path))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            SystemFileSystem.delete(backupPath, mustExist = false)
        }
    }

    @Test
    fun missingConfigLoadsAsAbsentWithoutCreatingAFile() {
        val path = temporaryConfigPath()

        assertNull(loadEngHubConfigIfPresent(path))
        assertFalse(SystemFileSystem.exists(path))
    }

    @Test
    fun savingPromotesVerifiedConfigAndRotatesTheValidPrimaryToBackup() {
        val path = temporaryConfigPath()
        val pendingPath = Path("$path.new")
        val backupPath = Path("$path.bak")
        val original = EngHubConfig(gitHubAuthor = "octocat")
        val replacement = EngHubConfig(gitHubAuthor = "hubot")
        writeText(path, lenientJson.encodeToString(EngHubConfig.serializer(), original))

        try {
            saveEngHubConfig(replacement, path)

            assertEquals(replacement, loadEngHubConfigIfPresent(path))
            assertEquals(original, loadEngHubConfigIfPresent(backupPath))
            assertFalse(SystemFileSystem.exists(pendingPath))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            SystemFileSystem.delete(pendingPath, mustExist = false)
            SystemFileSystem.delete(backupPath, mustExist = false)
        }
    }

    @Test
    fun pendingVerificationFailureUsesAnEngHubConfigWriteExceptionAndPreservesPrimary() {
        val path = temporaryConfigPath()
        val pendingPath = Path("$path.new")
        val original = EngHubConfig(gitHubAuthor = "octocat")
        writeText(path, lenientJson.encodeToString(EngHubConfig.serializer(), original))

        try {
            val error = assertFailsWith<EngHubConfigWriteException> {
                saveEngHubConfig(EngHubConfig(gitHubAuthor = "hubot"), path, verifyPending = { null })
            }

            assertTrue(error.cause is IllegalStateException)
            assertEquals(original, loadEngHubConfigIfPresent(path))
            assertFalse(SystemFileSystem.exists(pendingPath))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            SystemFileSystem.delete(pendingPath, mustExist = false)
        }
    }

    @Test
    fun storageFailureUsesAnEngHubConfigWriteException() {
        val missingDirectory = Path(SystemTemporaryDirectory, "eng-hub-missing-${Random.nextLong()}")
        val path = Path(missingDirectory, "eng-hub-config.json")

        assertFailsWith<EngHubConfigWriteException> {
            saveEngHubConfig(EngHubConfig(gitHubAuthor = "hubot"), path)
        }
        assertFalse(SystemFileSystem.exists(path))
    }

    @Test
    fun usesEngHubConfigFileName() {
        assertTrue(engHubConfigPath.toString().endsWith("eng-hub-config.json"))
    }

    private fun temporaryConfigPath(): Path = Path(SystemTemporaryDirectory, "eng-hub-config-${Random.nextLong()}.json")

    private fun writeText(path: Path, text: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }
}
