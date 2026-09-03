package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.representativeEngHubConfig
import com.github.karlsabo.devlake.enghub.viewmodel.GITHUB_SECRET_SAVE_ERROR
import com.github.karlsabo.devlake.enghub.viewmodel.LOCAL_REPOSITORY_DUPLICATE_ERROR
import com.github.karlsabo.devlake.enghub.viewmodel.ORGANIZATION_ID_DUPLICATE_ERROR
import com.github.karlsabo.devlake.enghub.viewmodel.POLL_INTERVAL_ERROR
import com.github.karlsabo.devlake.enghub.viewmodel.SETTINGS_PERSISTENCE_ERROR
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubSettingsScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysAllLoadedSettingsAndNeverAddsTheTokenToTextSemantics() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        assertField("github-token-path", "/secrets/github.json")
        assertField("github-token", "••••••••")
        assertField("organization-0", "acme")
        assertField("organization-1", "widgets")
        assertField("github-author", "octocat")
        assertField("poll-interval", "300")
        assertField("repositories-base-dir", "/workspace")
        assertField("worktree-poll-interval", "60")
        assertField("repository-0-path", "/workspace/api")
        assertField("repository-0-command-0", "cp .env.example .env")
        assertField("repository-0-command-1", "direnv allow")
        assertField("repository-1-path", "/workspace/web")
        assertField("alert-triage-where-to-look", "- PagerDuty: inspect the active incident")
        assertField("planning-markdown-dir", "/workspace/plans")
        assertField("setup-shell", "/bin/bash")
        onAllNodesWithText("github_pat_private", substring = true).assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsAndBrowsesForTheGitHubSecretPath() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = ""),
            gitHubSecret = GitHubSecret(githubToken = ""),
        )
        var typedPath: String? = null
        var browseCount = 0
        setContent {
            var screenState by remember { mutableStateOf(state) }
            MaterialTheme {
                EngHubSettingsScreen(
                    state = screenState,
                    actions = EngHubSettingsActions(
                        onGitHubTokenPathChange = {
                            typedPath = it
                            screenState = screenState.copy(gitHubTokenPath = it)
                        },
                        onChooseGitHubTokenPath = { browseCount += 1 },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("github-token-path").performTextReplacement("/secrets/github.json")
        onNodeWithTag("github-token-path-browse").performClick()

        assertEquals("/secrets/github.json", typedPath)
        assertEquals(1, browseCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun browsesForRepositoriesBaseDirectoryAndDisplaysTheSelection() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(repositoriesBaseDir = ""),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        setContent {
            var screenState by remember { mutableStateOf(state) }
            MaterialTheme {
                EngHubSettingsScreen(
                    state = screenState,
                    actions = EngHubSettingsActions(
                        onChooseRepositoriesBaseDir = {
                            screenState = screenState.copy(repositoriesBaseDir = "/workspace")
                        },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("repositories-base-dir-browse").performScrollTo().performClick()

        onNodeWithTag("repositories-base-dir").assertTextContains("/workspace")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun entersAndAddsALocalRepository() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(
                localRepositories = representativeEngHubConfig().localRepositories.take(1),
            ),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var repositoryDraft: String? = null
        var addCount = 0
        setContent {
            var screenState by remember { mutableStateOf(state) }
            MaterialTheme {
                EngHubSettingsScreen(
                    state = screenState,
                    actions = EngHubSettingsActions(
                        onLocalRepositoryDraftChange = {
                            repositoryDraft = it
                            screenState = screenState.copy(localRepositoryDraft = it)
                        },
                        onAddLocalRepository = { addCount += 1 },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("repository-new").performScrollTo().performTextReplacement("/workspace/web")
        onNodeWithTag("repository-add").performScrollTo().performClick()

        assertEquals("/workspace/web", repositoryDraft)
        assertEquals(1, addCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun typesAConfiguredRepositoryPath() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(
                localRepositories = listOf(LocalRepositoryConfig("/workspace/old", listOf("direnv allow"))),
            ),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedPath: Pair<Int, String>? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(
                        onLocalRepositoryPathChange = { index, path -> editedPath = index to path },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("repository-0-path").performScrollTo().performTextReplacement("/workspace/new")

        assertEquals(0 to "/workspace/new", editedPath)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun browsesForAConfiguredRepositoryPath() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(
                localRepositories = listOf(
                    LocalRepositoryConfig(
                        path = "/workspace/old",
                        setupCommands = listOf("direnv allow"),
                    ),
                ),
            ),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var selectedIndex: Int? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(
                        onChooseLocalRepositoryPath = { selectedIndex = it },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("repository-0-path-browse").performScrollTo().performClick()

        assertEquals(0, selectedIndex)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun removesTheSelectedLocalRepository() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(
                localRepositories = listOf(
                    LocalRepositoryConfig(path = "/workspace/api"),
                    LocalRepositoryConfig(path = "/workspace/old"),
                ),
            ),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var removedIndex: Int? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(onRemoveLocalRepository = { removedIndex = it }),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("repository-1-remove").performScrollTo().performClick()

        assertEquals(1, removedIndex)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun invokesUndoForTheRecentlyRemovedLocalRepository() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(localRepositories = emptyList()),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        ).copy(removedLocalRepositoryPath = "/workspace/api")
        var undoCount = 0
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(onUndoLocalRepositoryRemoval = { undoCount += 1 }),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("repository-removal-undo-action").performScrollTo().performClick()

        assertEquals(1, undoCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysDuplicateLocalRepositoryErrorInline() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        ).copy(
            localRepositoryDraft = "/workspace/web/",
            localRepositoryError = LOCAL_REPOSITORY_DUPLICATE_ERROR,
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        onNodeWithTag("repository-new").performScrollTo().assertTextContains("/workspace/web/")
        onNodeWithTag("repository-new-error").assertTextContains(LOCAL_REPOSITORY_DUPLICATE_ERROR)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun entersAndAddsAnOrganizationId() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(organizationIds = listOf("acme")),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var organizationIdDraft: String? = null
        var addCount = 0
        setContent {
            var screenState by remember { mutableStateOf(state) }
            MaterialTheme {
                EngHubSettingsScreen(
                    state = screenState,
                    actions = EngHubSettingsActions(
                        onOrganizationIdDraftChange = {
                            organizationIdDraft = it
                            screenState = screenState.copy(organizationIdDraft = it)
                        },
                        onAddOrganizationId = { addCount += 1 },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("organization-new").performScrollTo().performTextReplacement("widgets")
        onNodeWithTag("organization-add").performClick()

        assertEquals("widgets", organizationIdDraft)
        assertEquals(1, addCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun removesTheSelectedOrganizationId() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(organizationIds = listOf("acme", "example")),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var removedIndex: Int? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(onRemoveOrganizationId = { removedIndex = it }),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("organization-1-remove").performScrollTo().performClick()

        assertEquals(1, removedIndex)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysDuplicateOrganizationIdErrorInline() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        ).copy(
            organizationIdDraft = "acme",
            organizationIdError = ORGANIZATION_ID_DUPLICATE_ERROR,
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        onNodeWithTag("organization-new").performScrollTo().assertTextContains("acme")
        onNodeWithTag("organization-new-error").assertTextContains(ORGANIZATION_ID_DUPLICATE_ERROR)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun replacesTheMaskedGitHubTokenWithoutExposingItToTextSemantics() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var replacement: String? = null
        setContent {
            var screenState by remember { mutableStateOf(state) }
            MaterialTheme {
                EngHubSettingsScreen(
                    state = screenState,
                    actions = EngHubSettingsActions(
                        onGitHubTokenChange = { token ->
                            replacement = token
                            screenState = screenState.copy(gitHubToken = screenState.gitHubToken.withDraft(token))
                        },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("github-token").performTextReplacement("github_pat_new")

        assertEquals("github_pat_new", replacement)
        onAllNodesWithText("github_pat_new", substring = true).assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysGitHubSecretWriteFailureWithoutExposingTheToken() = runComposeUiTest {
        val token = "github_pat_private"
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = token),
        ).copy(gitHubTokenError = GITHUB_SECRET_SAVE_ERROR)
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        onNodeWithTag("github-token-error").assertTextContains(GITHUB_SECRET_SAVE_ERROR)
        onAllNodesWithText(token, substring = true).assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysAnActionableSettingsPersistenceError() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(gitHubAuthor = "octocat"),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        ).copy(
            gitHubAuthor = "hubot",
            persistenceError = SETTINGS_PERSISTENCE_ERROR,
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        onNodeWithTag("settings-persistence-error").assertTextContains(SETTINGS_PERSISTENCE_ERROR)
        onNodeWithTag("github-author").performScrollTo().assertTextContains("hubot")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsGitHubAuthorWithoutASaveAction() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedAuthor: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(onGitHubAuthorChange = { editedAuthor = it }),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("github-author").performScrollTo().performTextReplacement("hubot")

        assertEquals("hubot", editedAuthor)
        onAllNodesWithText("Save").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysAnActionablePollingIntervalError() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        ).copy(
            pollIntervalSeconds = "0",
            pollIntervalError = POLL_INTERVAL_ERROR,
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        onNodeWithTag("poll-interval").performScrollTo().assertTextContains("0")
        onNodeWithTag("poll-interval-error").assertTextContains(POLL_INTERVAL_ERROR)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsWorktreePollingInterval() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(worktreePollIntervalMs = 120_000),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedInterval: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(
                        onWorktreePollIntervalChange = { editedInterval = it },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("worktree-poll-interval").performScrollTo().performTextReplacement("60")

        assertEquals("60", editedInterval)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsMultilineAlertTriageGuidance() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedGuidance: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(
                        onAlertTriageWhereToLookChange = { editedGuidance = it },
                    ),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        val guidance = "- PagerDuty: inspect the incident\n- Grafana: inspect service health"
        onNodeWithTag("alert-triage-where-to-look").performScrollTo().performTextReplacement(guidance)

        assertEquals(guidance, editedGuidance)
        onAllNodesWithText("LLM skill templates").assertCountEquals(1)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsSetupShell() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(setupShell = "/bin/zsh"),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedShell: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(onSetupShellChange = { editedShell = it }),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("setup-shell").performScrollTo().performTextReplacement("/bin/bash")

        assertEquals("/bin/bash", editedShell)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsPollingInterval() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedPollInterval: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    actions = EngHubSettingsActions(onPollIntervalChange = { editedPollInterval = it }),
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("poll-interval").performScrollTo().performTextReplacement("301")

        assertEquals("301", editedPollInterval)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.assertField(tag: String, value: String) {
        onNodeWithTag(tag).performScrollTo().assertTextContains(value)
    }
}
