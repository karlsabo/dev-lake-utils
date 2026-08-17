package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

private suspend fun awaitRebaseCall(api: RecordingGitWorktreeApi, call: BranchNeedsRebaseCall) {
    withTimeout(2_000.milliseconds) {
        while (call !in api.branchNeedsRebaseCalls) delay(1.milliseconds)
    }
}

private fun pollingJobs(viewModel: EngHubViewModel) = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

private suspend fun cancelJobs(jobs: Set<Job>) {
    jobs.forEach { job ->
        job.cancel()
        job.join()
    }
}

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
    fun addingLocalRepositoryShowsBasicRowsWhenEnrichmentFails() = runBlocking {
        val repositoryWorktrees = RepositoryWorktrees(
            rootPath = DEV_LAKE_ROOT,
            selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
            worktrees = listOf(
                Worktree(path = DEV_LAKE_ROOT, branch = "feature/base-pr", commitHash = "abc123"),
                Worktree(
                    path = DEV_LAKE_SELECTED_WORKTREE,
                    branch = "feature/stacked-pr",
                    commitHash = "def456",
                ),
            ),
        )
        val rebaseCall = BranchNeedsRebaseCall(DEV_LAKE_ROOT, "feature/base-pr", "feature/stacked-pr")
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(DEV_LAKE_SELECTED_WORKTREE to repositoryWorktrees),
            responses = RecordingGitWorktreeApiResponses(
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
                branchNeedsRebaseFailure = IllegalStateException("rev-list failed"),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        awaitRebaseCall(api, rebaseCall)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.refreshRequest == null
            }.single()
        }
        assertEquals(
            listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
            configWriter.savedConfigs.value.single().localRepositories,
        )
        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })
        assertEquals(true, repository.isExpanded)
        assertEquals(false, repository.isLoading)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun refreshPreventsStaleAddEnrichmentFromReplacingMetadata() = runBlocking {
        val addEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseAddEnrichment = CompletableDeferred<Unit>()
        val parentBranches = mutableMapOf<String, String>()
        val enrichmentCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                addEnrichmentStarted.complete(Unit)
                runBlocking { releaseAddEnrichment.await() }
                parentBranches["feature/stacked-pr"] = "old-main"
            }
            trySend {
                parentBranches.clear()
                parentBranches["feature/stacked-pr"] = "new-main"
            }
        }
        val repositoryWorktrees = RepositoryWorktrees(
            rootPath = DEV_LAKE_ROOT,
            selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
            worktrees = stackedParentWorktrees(commitSuffix = "old"),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(DEV_LAKE_SELECTED_WORKTREE to repositoryWorktrees),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { stackedParentWorktrees(commitSuffix = "new") },
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to parentBranches),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferWorktreeParentBranches = {
                    enrichmentCalls.tryReceive().getOrNull()?.invoke()
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 25),
        )
        val pollingJobs = pollingJobs(viewModel)

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { addEnrichmentStarted.await() }
        val addJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single { it !in pollingJobs }
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees?.singleOrNull {
                    it.branch == "feature/stacked-pr"
                }?.parentBranch == "new-main"
            }
        }
        cancelJobs(pollingJobs)

        releaseAddEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { addJob.join() }

        val stackedWorktree = viewModel.localRepositoriesStateFlow.value.single().worktrees.single {
            it.branch == "feature/stacked-pr"
        }
        assertEquals("new-main", stackedWorktree.parentBranch)
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
    fun expandingConfiguredRepositoryPublishesLoadingStateBeforeDiscoveryCompletes() = runBlocking {
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

        val loadingRepository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(true, loadingRepository.isExpanded)
        assertEquals(true, loadingRepository.isLoading)
        withTimeout(2_000.milliseconds) { listStarted.await() }

        releaseList.complete(Unit)
        val loadedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                !repositories.single().isLoading
            }.single()
        }
        assertEquals(listOf("main"), loadedRepository.worktrees.map { it.branch })
    }

    @Test
    fun expandingConfiguredRepositoryShowsBasicRowsWhileStackEnrichmentIsRunning() = runBlocking {
        val enrichmentStarted = CompletableDeferred<Unit>()
        val releaseEnrichment = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/stacked-pr",
                            commitHash = "def456",
                            isDirty = true,
                        ),
                    ),
                ),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "main"),
                ),
                branchNeedsRebaseByCall = mapOf(
                    BranchNeedsRebaseCall(DEV_LAKE_ROOT, "main", "feature/stacked-pr") to true,
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferWorktreeParentBranches = {
                    enrichmentStarted.complete(Unit)
                    runBlocking { releaseEnrichment.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { enrichmentStarted.await() }

        val repository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(true, repository.isExpanded)
        assertEquals(true, repository.isLoading)
        assertEquals(listOf("main", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })

        releaseEnrichment.complete(Unit)
        val enrichedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                !repositories.single().isLoading
            }.single()
        }
        val stackedWorktree = enrichedRepository.worktrees.single { it.branch == "feature/stacked-pr" }
        assertEquals("main", stackedWorktree.parentBranch)
        assertEquals(true, stackedWorktree.needsRebase)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, stackedWorktree.path)
        assertEquals(true, stackedWorktree.isDirty)
        assertEquals(false, stackedWorktree.isRoot)
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
                !repositories.single().isLoading && repositories.single().worktrees.size == 2
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
    fun enrichmentFailureStopsLoadingAndPreservesBasicRowsWithoutActionError() = runBlocking {
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
                val repository = repositories.single()
                repository.isExpanded && !repository.isLoading && repository.worktrees.size == 2
            }.single()
        }

        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }
}

class EngHubLocalRepositoryRefreshViewModelTest {

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
    fun pollKeepsExistingEnrichmentWhileReplacementEnrichmentIsRunning() = runBlocking {
        val pollEnrichmentStarted = CompletableDeferred<Unit>()
        val releasePollEnrichment = CompletableDeferred<Unit>()
        val enrichmentCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {}
            trySend {
                pollEnrichmentStarted.complete(Unit)
                runBlocking { releasePollEnrichment.await() }
            }
        }
        val worktreeLists = Channel<List<Worktree>>(capacity = 2).apply {
            trySend(stackedPollWorktrees())
            trySend(stackedPollWorktrees(isDirty = true))
        }
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesForRepoPath = { worktreeLists.tryReceive().getOrThrow() },
                    parentBranchesByRepoPath = mapOf(
                        DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "main"),
                    ),
                    branchNeedsRebaseByCall = mapOf(
                        BranchNeedsRebaseCall(DEV_LAKE_ROOT, "main", "feature/stacked-pr") to true,
                    ),
                ),
                callbacks = RecordingGitWorktreeApiCallbacks(
                    onInferWorktreeParentBranches = {
                        enrichmentCalls.tryReceive().getOrThrow().invoke()
                    },
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 250),
        )
        val pollingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val stackedWorktree = repositories.single().worktrees.singleOrNull {
                    it.branch == "feature/stacked-pr"
                }
                !repositories.single().isLoading && stackedWorktree?.parentBranch == "main"
            }
        }
        withTimeout(2_000.milliseconds) { pollEnrichmentStarted.await() }

        val stackedWorktree = viewModel.localRepositoriesStateFlow.value.single().worktrees.single {
            it.branch == "feature/stacked-pr"
        }
        assertEquals(true, stackedWorktree.isDirty)
        assertEquals("main", stackedWorktree.parentBranch)
        assertEquals(true, stackedWorktree.needsRebase)

        releasePollEnrichment.complete(Unit)
        pollingJobs.forEach { job ->
            job.cancel()
            job.join()
        }
    }

    @Test
    fun refreshEnrichmentFailureClearsStaleEnrichmentAndAllowsNextRefresh() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to stackedPollWorktrees()),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "main"),
                ),
                branchNeedsRebaseFailure = IllegalStateException("rev-list failed"),
            ),
        )
        val fixture = createRefreshControllerFixture(api)
        fixture.state.localRepositories.value = fixture.state.localRepositories.value.map { repository ->
            repository.copy(
                worktrees = stackedPollWorktrees().toLocalWorktreeUiStates(
                    repositoryRootPath = DEV_LAKE_ROOT,
                    parentBranchesByChildBranch = mapOf("feature/stacked-pr" to "main"),
                    needsRebaseByChildBranch = mapOf("feature/stacked-pr" to true),
                ),
            )
        }

        fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "test refresh")
        fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "retry test refresh")

        val repository = fixture.state.localRepositories.value.single()
        assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(listOf("main", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })
        assertEquals(false, repository.isLoading)
        assertEquals(null, repository.refreshRequest)
    }

    @Test
    fun refreshPreservesLoadingWhileSupersedingInitialExpansionDiscovery() = runBlocking {
        val expansionListStarted = CompletableDeferred<Unit>()
        val refreshListStarted = CompletableDeferred<Unit>()
        val releaseExpansionList = CompletableDeferred<Unit>()
        val releaseRefreshList = CompletableDeferred<Unit>()
        val listCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                expansionListStarted.complete(Unit)
                runBlocking { releaseExpansionList.await() }
            }
            trySend {
                refreshListStarted.complete(Unit)
                runBlocking { releaseRefreshList.await() }
            }
        }
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = mapOf(
                        DEV_LAKE_ROOT to listOf(
                            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        ),
                    ),
                ),
                callbacks = RecordingGitWorktreeApiCallbacks(
                    onListWorktrees = { listCalls.tryReceive().getOrThrow().invoke() },
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 25),
        )
        val pollingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { expansionListStarted.await() }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!.children
            .single { it !in pollingJobs }
        withTimeout(2_000.milliseconds) { refreshListStarted.await() }

        val loadingRepository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(true, loadingRepository.isExpanded)
        assertEquals(true, loadingRepository.isLoading)
        assertEquals(emptyList(), loadingRepository.worktrees)

        releaseRefreshList.complete(Unit)
        val refreshedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val repository = repositories.single()
                !repository.isLoading && repository.worktrees.isNotEmpty()
            }.single()
        }
        cancelJobs(pollingJobs)
        releaseExpansionList.complete(Unit)
        withTimeout(2_000.milliseconds) { expansionJob.join() }

        assertEquals(listOf("main"), refreshedRepository.worktrees.map { it.branch })
        assertEquals(
            listOf("main"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
    }

    @Test
    fun failedPollPermanentlyInvalidatesOlderExpansionEnrichment() = runBlocking {
        val expansionEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseExpansionEnrichment = CompletableDeferred<Unit>()
        val pollFailed = CompletableDeferred<Unit>()
        val listCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {}
            trySend {
                pollFailed.complete(Unit)
                error("git worktree list failed")
            }
        }
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/stacked-pr",
                            commitHash = "def456",
                        ),
                    ),
                ),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "main")),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = { listCalls.tryReceive().getOrThrow().invoke() },
                onInferWorktreeParentBranches = {
                    expansionEnrichmentStarted.complete(Unit)
                    runBlocking { releaseExpansionEnrichment.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 25),
        )
        val pollingJobs = pollingJobs(viewModel)

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { expansionEnrichmentStarted.await() }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single { it !in pollingJobs }
        withTimeout(2_000.milliseconds) { pollFailed.await() }
        val repositoryAfterFailedPoll = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first {
                it.single().refreshRequest == null && api.listWorktreeRepoPaths.size >= 2
            }.single()
        }
        cancelJobs(pollingJobs)

        assertEquals(false, repositoryAfterFailedPoll.isLoading)
        assertEquals(null, repositoryAfterFailedPoll.operationRequest)
        val stackedWorktree = repositoryAfterFailedPoll.worktrees.single { it.branch == "feature/stacked-pr" }
        assertEquals(null, stackedWorktree.parentBranch)

        releaseExpansionEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { expansionJob.join() }
        val repositoryAfterLateEnrichment = viewModel.localRepositoriesStateFlow.value.single()

        assertEquals(repositoryAfterFailedPoll, repositoryAfterLateEnrichment)
    }

    @Test
    fun publishedRefreshPreventsInFlightExpansionFromReplacingWorktrees() = runBlocking {
        val expansionEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseExpansionEnrichment = CompletableDeferred<Unit>()
        val refreshEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseRefreshEnrichment = CompletableDeferred<Unit>()
        val enrichmentCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                expansionEnrichmentStarted.complete(Unit)
                runBlocking { releaseExpansionEnrichment.await() }
            }
            trySend {
                refreshEnrichmentStarted.complete(Unit)
                runBlocking { releaseRefreshEnrichment.await() }
            }
        }
        val worktreeLists = Channel<List<Worktree>>(capacity = 2).apply {
            trySend(listOf(Worktree(path = DEV_LAKE_ROOT, branch = "old-main", commitHash = "old")))
            trySend(listOf(Worktree(path = DEV_LAKE_ROOT, branch = "new-main", commitHash = "new")))
        }
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesForRepoPath = { worktreeLists.tryReceive().getOrThrow() },
                ),
                callbacks = RecordingGitWorktreeApiCallbacks(
                    onInferWorktreeParentBranches = {
                        enrichmentCalls.tryReceive().getOrNull()?.invoke()
                    },
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 25),
        )
        val pollingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { expansionEnrichmentStarted.await() }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!.children
            .single { it !in pollingJobs }
        withTimeout(2_000.milliseconds) { refreshEnrichmentStarted.await() }
        val refreshedRepository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(listOf("new-main"), refreshedRepository.worktrees.map { it.branch })
        assertEquals(null, refreshedRepository.operationRequest)

        releaseExpansionEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { expansionJob.join() }
        assertEquals(
            listOf("new-main"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )

        releaseRefreshEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().refreshRequest == null
            }
        }
        cancelJobs(pollingJobs)
    }
}

class EngHubLocalRepositoryConcurrencyViewModelTest {

    @Test
    fun collapseWhileDiscoveryIsSuspendedIgnoresLateDiscovery() = runBlocking {
        val discoveryStarted = CompletableDeferred<Unit>()
        val releaseDiscovery = CompletableDeferred<Unit>()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "late-main", commitHash = "late"),
                    ),
                ),
                onListWorktrees = {
                    discoveryStarted.complete(Unit)
                    runBlocking { releaseDiscovery.await() }
                },
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        val existingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { discoveryStarted.await() }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single { it !in existingJobs }
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        releaseDiscovery.complete(Unit)
        withTimeout(2_000.milliseconds) { expansionJob.join() }

        val repository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(false, repository.isExpanded)
        assertEquals(false, repository.isLoading)
        assertEquals(emptyList(), repository.worktrees)
    }

    @Test
    fun collapseWhileEnrichmentIsSuspendedIgnoresLateEnrichment() = runBlocking {
        val enrichmentStarted = CompletableDeferred<Unit>()
        val releaseEnrichment = CompletableDeferred<Unit>()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to stackedPollWorktrees()),
                    parentBranchesByRepoPath = mapOf(
                        DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "main"),
                    ),
                    branchNeedsRebaseByCall = mapOf(
                        BranchNeedsRebaseCall(DEV_LAKE_ROOT, "main", "feature/stacked-pr") to true,
                    ),
                ),
                callbacks = RecordingGitWorktreeApiCallbacks(
                    onInferWorktreeParentBranches = {
                        enrichmentStarted.complete(Unit)
                        runBlocking { releaseEnrichment.await() }
                    },
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        val existingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { enrichmentStarted.await() }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single { it !in existingJobs }
        val discoveredWorktrees = viewModel.localRepositoriesStateFlow.value.single().worktrees
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        releaseEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { expansionJob.join() }

        val repository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(false, repository.isExpanded)
        assertEquals(false, repository.isLoading)
        assertEquals(discoveredWorktrees, repository.worktrees)
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })
    }

    @Test
    fun olderRefreshDiscoveryCannotOverwriteNewerRefresh() = runBlocking {
        val oldDiscoveryStarted = CompletableDeferred<Unit>()
        val releaseOldDiscovery = CompletableDeferred<Unit>()
        val api = overlappingRefreshApi(
            blockOldDiscovery = oldDiscoveryStarted to releaseOldDiscovery,
        )
        val fixture = createRefreshControllerFixture(api)

        val olderRefresh = launch(Dispatchers.IO) {
            fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "older test refresh")
        }
        withTimeout(2_000.milliseconds) { oldDiscoveryStarted.await() }
        fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "newer test refresh")
        val newerWorktrees = fixture.state.localRepositories.value.single().worktrees

        releaseOldDiscovery.complete(Unit)
        withTimeout(2_000.milliseconds) { olderRefresh.join() }

        assertEquals(newerWorktrees, fixture.state.localRepositories.value.single().worktrees)
        assertNewRefreshWorktrees(newerWorktrees)
    }

    @Test
    fun olderRefreshEnrichmentCannotOverwriteNewerRefresh() = runBlocking {
        val oldEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseOldEnrichment = CompletableDeferred<Unit>()
        val api = overlappingRefreshApi(
            blockOldEnrichment = oldEnrichmentStarted to releaseOldEnrichment,
        )
        val fixture = createRefreshControllerFixture(api)

        val olderRefresh = launch(Dispatchers.IO) {
            fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "older test refresh")
        }
        withTimeout(2_000.milliseconds) { oldEnrichmentStarted.await() }
        fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "newer test refresh")
        val newerWorktrees = fixture.state.localRepositories.value.single().worktrees

        releaseOldEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { olderRefresh.join() }

        assertEquals(newerWorktrees, fixture.state.localRepositories.value.single().worktrees)
        assertNewRefreshWorktrees(newerWorktrees)
    }

    @Test
    fun expansionStartedAfterRefreshPreventsStaleEnrichmentFromReplacingMetadata() = runBlocking {
        val refreshEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseRefreshEnrichment = CompletableDeferred<Unit>()
        val parentBranches = mutableMapOf<String, String>()
        val enrichmentCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                refreshEnrichmentStarted.complete(Unit)
                runBlocking { releaseRefreshEnrichment.await() }
                parentBranches["feature/stacked-pr"] = "main"
            }
            trySend { parentBranches.clear() }
        }
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "main"),
            Worktree(
                path = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/stacked-pr",
                commitHash = "feature",
            ),
        )
        val staleRebaseCall = BranchNeedsRebaseCall(DEV_LAKE_ROOT, "main", "feature/stacked-pr")
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to parentBranches),
                branchNeedsRebaseByCall = mapOf(staleRebaseCall to true),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferWorktreeParentBranches = {
                    enrichmentCalls.tryReceive().getOrNull()?.invoke()
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(worktreePollIntervalMs = 25),
        )
        val pollingJobs = pollingJobs(viewModel)
        withTimeout(2_000.milliseconds) { refreshEnrichmentStarted.await() }

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        val expandedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val repository = repositories.single()
                repository.isExpanded && !repository.isLoading && repository.worktrees.size == 2
            }.single()
        }
        assertEquals(null, expandedRepository.worktrees.single { it.branch == "feature/stacked-pr" }.parentBranch)

        releaseRefreshEnrichment.complete(Unit)
        awaitRebaseCall(api, staleRebaseCall)
        cancelJobs(pollingJobs)

        val stackedWorktree = viewModel.localRepositoriesStateFlow.value.single().worktrees.single {
            it.branch == "feature/stacked-pr"
        }
        assertEquals(null, stackedWorktree.parentBranch)
        assertEquals(false, stackedWorktree.needsRebase)
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
        assertEquals(true, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isLoading)
        assertEquals(emptyList(), viewModel.localRepositoriesStateFlow.value.single().worktrees)
    }

    @Test
    fun staleExpansionFailureDoesNotReportErrorDuringNewExpansion() = runBlocking {
        val firstListStarted = CompletableDeferred<Unit>()
        val secondListStarted = CompletableDeferred<Unit>()
        val releaseFirstList = CompletableDeferred<Unit>()
        val releaseSecondList = CompletableDeferred<Unit>()
        val listCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                firstListStarted.complete(Unit)
                runBlocking { releaseFirstList.await() }
            }
            trySend {
                secondListStarted.complete(Unit)
                runBlocking { releaseSecondList.await() }
            }
        }
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    listWorktreesFailure = IllegalStateException("git worktree list failed"),
                ),
                callbacks = RecordingGitWorktreeApiCallbacks(
                    onListWorktrees = { listCalls.tryReceive().getOrThrow().invoke() },
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        val existingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { firstListStarted.await() }
        val firstExpansionJob = viewModel.viewModelScope.coroutineContext[Job]!!
            .children
            .single { it !in existingJobs }
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { secondListStarted.await() }

        releaseFirstList.complete(Unit)
        withTimeout(2_000.milliseconds) { firstExpansionJob.join() }

        assertEquals(null, viewModel.actionErrorStateFlow.value)
        assertEquals(true, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
        assertEquals(true, viewModel.localRepositoriesStateFlow.value.single().isLoading)

        releaseSecondList.complete(Unit)
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("git worktree list failed", actionError?.message)
        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isLoading)
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

        val existingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            listStarted.await()
        }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!
            .children
            .single { it !in existingJobs }

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isLoading)

        releaseList.complete(Unit)
        withTimeout(2_000.milliseconds) { expansionJob.join() }

        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
    }
}

private data class RefreshControllerFixture(
    val state: EngHubViewModelState,
    val controller: LocalRepositoryController,
)

private fun createRefreshControllerFixture(api: RecordingGitWorktreeApi): RefreshControllerFixture {
    val configWriter = RecordingEngHubConfigWriter()
    val services = EngHubWorktreeServices(
        gitWorktreeApi = api,
        worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = api),
        directoryPicker = LocalRepositoryNoOpDirectoryPicker(),
        configWriter = configWriter,
    )
    val state = EngHubViewModelState(
        config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
        configWriter = configWriter,
        worktreeSetupCoordinator = services.worktreeSetupCoordinator,
        notificationIgnoreStore = NoOpNotificationIgnoreStore(),
    )
    val controller = LocalRepositoryController(
        viewModel = object : ViewModel() {},
        state = state,
        worktreeServices = services,
        errorReporter = ActionErrorReporter(state),
    )
    return RefreshControllerFixture(state, controller)
}

private fun overlappingRefreshApi(
    blockOldDiscovery: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
    blockOldEnrichment: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
): RecordingGitWorktreeApi {
    val discoveryCalls = Channel<Int>(capacity = 2).apply {
        trySend(1)
        trySend(2)
    }
    val enrichmentCalls = Channel<Int>(capacity = 2).apply {
        if (blockOldDiscovery == null) trySend(1)
        trySend(2)
    }
    val parentBranches = mutableMapOf<String, String>()
    return RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesForRepoPath = {
                val call = discoveryCalls.tryReceive().getOrThrow()
                if (call == 1) blockOldDiscovery?.awaitBlockedCall()
                refreshWorktrees(call)
            },
            parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to parentBranches),
            branchNeedsRebaseByCall = mapOf(
                BranchNeedsRebaseCall(DEV_LAKE_ROOT, "new-main", "feature/stacked-pr") to true,
            ),
        ),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onInferWorktreeParentBranches = {
                val call = enrichmentCalls.tryReceive().getOrThrow()
                if (call == 1) blockOldEnrichment?.awaitBlockedCall()
                parentBranches["feature/stacked-pr"] = if (call == 1) "old-main" else "new-main"
            },
        ),
    )
}

private fun Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>.awaitBlockedCall() {
    first.complete(Unit)
    runBlocking { second.await() }
}

private fun refreshWorktrees(call: Int): List<Worktree> {
    val version = if (call == 1) "old" else "new"
    return listOf(
        Worktree(path = DEV_LAKE_ROOT, branch = "$version-main", commitHash = "$version-main"),
        Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/stacked-pr",
            commitHash = version,
            isDirty = call == 2,
        ),
    )
}

private fun assertNewRefreshWorktrees(worktrees: List<com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState>) {
    assertEquals(listOf("new-main", "feature/stacked-pr"), worktrees.map { it.branch })
    val stackedWorktree = worktrees.single { it.branch == "feature/stacked-pr" }
    assertEquals(true, stackedWorktree.isDirty)
    assertEquals("new-main", stackedWorktree.parentBranch)
    assertEquals(true, stackedWorktree.needsRebase)
}

private fun stackedParentWorktrees(commitSuffix: String): List<Worktree> = listOf(
    Worktree(path = DEV_LAKE_ROOT, branch = "old-main", commitHash = "old-main-$commitSuffix"),
    Worktree(path = "$DEV_LAKE_ROOT-new-main", branch = "new-main", commitHash = "new-main-$commitSuffix"),
    Worktree(
        path = DEV_LAKE_SELECTED_WORKTREE,
        branch = "feature/stacked-pr",
        commitHash = "feature-$commitSuffix",
    ),
)

private fun stackedPollWorktrees(isDirty: Boolean = false): List<Worktree> = listOf(
    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "main"),
    Worktree(
        path = DEV_LAKE_SELECTED_WORKTREE,
        branch = "feature/stacked-pr",
        commitHash = "feature",
        isDirty = isDirty,
    ),
)
