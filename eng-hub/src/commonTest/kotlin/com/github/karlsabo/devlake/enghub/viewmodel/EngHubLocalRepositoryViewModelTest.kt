package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalRepositoryViewModelTest {

    @Test
    fun addingLinkedWorktreePersistsCanonicalRootAndShowsSelectedBranch() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktrees = RepositoryWorktrees(
                rootPath = DEV_LAKE_ROOT,
                selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                worktrees = listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                    Worktree(
                        path = DEV_LAKE_SELECTED_WORKTREE,
                        branch = "feature/worktree-panel",
                        commitHash = "def456",
                    ),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)

        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == DEV_LAKE_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals(
            listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
            configWriter.savedConfigs.value.single().localRepositories,
        )
        assertEquals(listOf("dev-lake-utils"), repositories.map { it.name })
        assertEquals(listOf("main", "feature/worktree-panel"), repositories.single().worktrees.map { it.branch })
        assertEquals(listOf(true, false), repositories.single().worktrees.map { it.isRoot })
    }

    @Test
    fun addingLocalRepositoryPreservesExistingRepositoryWorktrees() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = devLakeAndDocsRepositoryWorktreesBySelectedPath(),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == DEV_LAKE_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        viewModel.addLocalRepository(DOCS_SELECTED_WORKTREE)
        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.size == 2 && repositories.any { it.path == DOCS_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE, DOCS_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals(
            listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT),
                LocalRepositoryConfig(path = DOCS_ROOT),
            ),
            configWriter.savedConfigs.value.last().localRepositories,
        )
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch },
        )
        assertEquals(
            listOf("main", "feature/notes"),
            repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch },
        )
    }

    @Test
    fun addingLocalRepositoryPersistsUnifiedEntryWithoutChangingExistingSetupCommands() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktrees = RepositoryWorktrees(
                rootPath = NEW_LOCAL_REPO_ROOT,
                selectedWorktreePath = NEW_LOCAL_REPO_ROOT,
                worktrees = listOf(
                    Worktree(path = NEW_LOCAL_REPO_ROOT, branch = "main", commitHash = "abc123"),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val existingRepository = LocalRepositoryConfig(
            path = EXAMPLE_WEB_ROOT,
            setupCommands = listOf("direnv allow", "direnv exec . idea ./"),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
            localRepositoryConfigs = listOf(existingRepository),
        )

        viewModel.addLocalRepository(NEW_LOCAL_REPO_ROOT)

        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == NEW_LOCAL_REPO_ROOT && it.worktrees.isNotEmpty() }
            }
        }
        val savedConfig = configWriter.savedConfigs.value.single()

        assertEquals(
            listOf(
                existingRepository,
                LocalRepositoryConfig(path = NEW_LOCAL_REPO_ROOT, setupCommands = emptyList()),
            ),
            savedConfig.localRepositories,
        )
        assertEquals(
            listOf("example-web", "new-local-repo"),
            repositories.map { it.name },
        )
    }

    @Test
    fun addingDuplicateLocalRepositorySetsErrorWithoutSavingOrChangingRepositories() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktrees = RepositoryWorktrees(
                rootPath = DEV_LAKE_ROOT,
                selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                worktrees = listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        val initialRepositories = viewModel.localRepositoriesStateFlow.value

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals("Repository already configured: $DEV_LAKE_ROOT", actionError?.message)
        assertEquals(emptyList(), configWriter.savedConfigs.value)
        assertEquals(initialRepositories, viewModel.localRepositoriesStateFlow.value)
    }

    @Test
    fun rendersConfiguredLocalRepositoryObjectsInFolderNameOrder() {
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = emptyMap(),
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(
                    path = "/workspace/example-service",
                    setupCommands = listOf("direnv allow"),
                ),
                LocalRepositoryConfig(
                    path = "/workspace/example-web",
                    setupCommands = listOf("direnv exec . idea ./"),
                ),
                LocalRepositoryConfig(path = "/workspace/example-worker"),
                LocalRepositoryConfig(path = "/workspace/example-infra"),
            ),
        )

        val repositories = viewModel.localRepositoriesStateFlow.value

        assertEquals(
            listOf("example-infra", "example-service", "example-web", "example-worker"),
            repositories.map { it.name },
        )
        assertEquals(
            listOf(
                "/workspace/example-infra",
                "/workspace/example-service",
                "/workspace/example-web",
                "/workspace/example-worker",
            ),
            repositories.map { it.path },
        )
    }

    @Test
    fun expandingConfiguredRepositoryListsWorktreesAndShowsBranchesWithDirtyStatus() = runBlocking {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/worktree-panel",
                            commitHash = "def456",
                            isDirty = true,
                        ),
                    ),
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().isExpanded && repositories.single().worktrees.size == 2
            }.single()
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(listOf("main", "feature/worktree-panel"), repository.worktrees.map { it.branch })
        assertEquals(listOf(false, true), repository.worktrees.map { it.isDirty })
        assertEquals(listOf(true, false), repository.worktrees.map { it.isRoot })
    }

    @Test
    fun expandingConfiguredRepositoryMapsRebaseNeededWorktreeState() = runBlocking {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "feature/base-pr", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/stacked-pr",
                            commitHash = "def456",
                        ),
                    ),
                ),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
                branchNeedsRebaseByCall = mapOf(
                    BranchNeedsRebaseCall(
                        repoPath = DEV_LAKE_ROOT,
                        parentBranch = "feature/base-pr",
                        childBranch = "feature/stacked-pr",
                    ) to true,
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val worktrees = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }.single().worktrees
        }

        assertEquals(
            listOf(BranchNeedsRebaseCall(DEV_LAKE_ROOT, "feature/base-pr", "feature/stacked-pr")),
            api.branchNeedsRebaseCalls,
        )
        assertEquals(false, worktrees.single { it.branch == "feature/base-pr" }.needsRebase)
        assertEquals(true, worktrees.single { it.branch == "feature/stacked-pr" }.needsRebase)
    }

    @Test
    fun expandingConfiguredRepositoryDefaultsRebaseNeededToFalseWhenCheckFails() = runBlocking {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "feature/base-pr", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/stacked-pr",
                            commitHash = "def456",
                        ),
                    ),
                ),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
                branchNeedsRebaseFailure = IllegalStateException("rev-list failed"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().isExpanded && repositories.single().worktrees.size == 2
            }.single()
        }

        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })
    }

    @Test
    fun worktreePollRefreshesUnifiedRepositoryEntriesWithoutRefreshingGitHubData() = runBlocking {
        val listCountsByRepo = mutableMapOf<String, Int>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = pollingWorktrees(listCountsByRepo),
            ),
        )
        val gitHubApi = RecordingGitHubApi(emptyMap())
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(
                    path = DEV_LAKE_ROOT,
                    setupCommands = listOf("direnv allow"),
                ),
                LocalRepositoryConfig(
                    path = DOCS_ROOT,
                    setupCommands = listOf("direnv exec . idea ./"),
                ),
            ),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 25),
            services = LocalRepositoryViewModelServices(
                gitHubApi = gitHubApi,
            ),
        )

        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val devLake = repositories.single { it.path == DEV_LAKE_ROOT }
                val docs = repositories.single { it.path == DOCS_ROOT }
                !devLake.isExpanded &&
                    !docs.isExpanded &&
                    devLake.worktrees.size == 2 &&
                    docs.worktrees.size == 1
            }
        }

        assertEquals(setOf(DEV_LAKE_ROOT, DOCS_ROOT), api.listWorktreeRepoPaths.toSet())
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch },
        )
        assertEquals(listOf(false, true), repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.isDirty })
        assertEquals(listOf("docs-main"), repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch })
        assertEquals(0, gitHubApi.openPullRequestCalls)
        assertEquals(0, gitHubApi.notificationListCalls)
    }

    @Test
    fun concurrentRepositoryExpansionsPreserveBothRepositoryStates() = runBlocking {
        val devLakeListStarted = CompletableDeferred<Unit>()
        val docsListStarted = CompletableDeferred<Unit>()
        val releaseLists = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                    ),
                    DOCS_ROOT to listOf(
                        Worktree(path = DOCS_ROOT, branch = "docs-main", commitHash = "123abc"),
                    ),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = { repoPath ->
                    when (repoPath) {
                        DEV_LAKE_ROOT -> devLakeListStarted.complete(Unit)
                        DOCS_ROOT -> docsListStarted.complete(Unit)
                    }
                    runBlocking { releaseLists.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT, DOCS_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        viewModel.toggleLocalRepositoryExpansion(DOCS_ROOT)
        withTimeout(2_000.milliseconds) {
            devLakeListStarted.await()
            docsListStarted.await()
        }

        releaseLists.complete(Unit)
        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.all { it.isExpanded && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(setOf(DEV_LAKE_ROOT, DOCS_ROOT), api.listWorktreeRepoPaths.toSet())
        assertEquals(listOf("main"), repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch })
        assertEquals(listOf("docs-main"), repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch })
    }

    @Test
    fun expandingConfiguredRepositoryFailureSetsActionErrorWithoutExpanding() = runBlocking {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                listWorktreesFailure = IllegalStateException("git worktree list failed"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals("git worktree list failed", actionError?.message)
        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
        assertEquals(emptyList(), viewModel.localRepositoriesStateFlow.value.single().worktrees)
    }

    @Test
    fun duplicateExpandClicksWhileLoadingDoNotStartStaleExpansionJobs() = runBlocking {
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                    ),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = {
                    listStarted.complete(Unit)
                    runBlocking { releaseList.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            listStarted.await()
        }

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)

        releaseList.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().isExpanded
            }
        }

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
    }
}
