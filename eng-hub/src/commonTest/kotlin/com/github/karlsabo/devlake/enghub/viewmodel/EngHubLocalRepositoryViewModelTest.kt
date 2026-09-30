package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.github.GitHubRepositoryIdentity
import com.github.karlsabo.system.OsFamily
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

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedEntryPaths)
        assertEquals(emptyList(), api.resolvedPaths)
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
        var addEnrichmentLookupServed = false
        val parentBranches = mutableMapOf("feature/stacked-pr" to "old-main")
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
                    if (!addEnrichmentLookupServed) {
                        addEnrichmentLookupServed = true
                        addEnrichmentStarted.complete(Unit)
                        runBlocking { releaseAddEnrichment.await() }
                    } else {
                        parentBranches["feature/stacked-pr"] = "new-main"
                    }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
        )
        val pollingJobs = pollingJobs(viewModel)

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { addEnrichmentStarted.await() }

        // The add's blocked enrichment runs to completion before the queued refresh enrichment, so
        // observing the refresh enrichment proves the stale add enrichment was discarded, not lost.
        releaseAddEnrichment.complete(Unit)
        val stackedWorktree = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees?.singleOrNull {
                    it.branch == "feature/stacked-pr"
                }?.parentBranch == "new-main"
            }.single().worktrees.single { it.branch == "feature/stacked-pr" }
        }
        cancelJobs(pollingJobs)

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

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE, DOCS_SELECTED_WORKTREE), api.resolvedEntryPaths)
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

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedEntryPaths)
        assertEquals("Repository already configured: $DEV_LAKE_ROOT", actionError?.message)
        assertEquals(emptyList(), configWriter.savedConfigs.value)
        assertEquals(initialRepositories, viewModel.localRepositoriesStateFlow.value)
    }

    @Test
    fun pickingMacOsCaseAndLexicalRepositoryAliasDoesNotWriteRefreshOrDuplicate() = runBlocking {
        val configuredPath = "/Users/me/repo"
        val selectedPath = "/users/ME/REPO-linked"
        val resolvedAlias = "/users/ME/./other/../REPO//"
        val repositoryWorktrees = RepositoryWorktrees(
            rootPath = resolvedAlias,
            selectedWorktreePath = selectedPath,
            worktrees = listOf(Worktree(path = resolvedAlias, branch = "main", commitHash = "abc123")),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(selectedPath to repositoryWorktrees),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val worktreeServices = EngHubWorktreeServices(
            gitWorktreeApi = api,
            worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = api),
            directoryPicker = object : DirectoryPicker {
                override suspend fun pickDirectory(title: String): String = selectedPath
            },
            configWriter = configWriter,
        )
        val state = EngHubViewModelState(
            config = EngHubConfig(localRepositories = localRepositoryConfigs(configuredPath)),
            configWriter = configWriter,
            worktreeSetupCoordinator = worktreeServices.worktreeSetupCoordinator,
            notificationIgnoreStore = NoOpNotificationIgnoreStore(),
        )
        val controller = LocalRepositoryController(
            viewModel = object : ViewModel() {},
            state = state,
            worktreeServices = worktreeServices,
            errorReporter = ActionErrorReporter(state),
            repositoryIdentity = { it.normalizedRepositoryPath(OsFamily.MACOS) },
        )
        val initialRepositories = state.localRepositories.value

        controller.pickAndAddLocalRepository()

        withTimeout(2_000.milliseconds) { state.actionErrors.first { it.current != null } }
        assertEquals(listOf(selectedPath), api.resolvedEntryPaths)
        assertEquals(emptyList(), configWriter.savedConfigs.value)
        assertEquals(emptyList(), api.listWorktreeRepoPaths)
        assertEquals(initialRepositories, state.localRepositories.value)
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
    fun repositoryDiscoveryResolvesGitHubOriginAndConfigRefreshPreservesIt() = runBlocking {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "feature/login", commitHash = "abc123"),
                    ),
                ),
                originUrlsByRepoPath = mapOf(
                    DEV_LAKE_ROOT to "git@github.com:acme/widgets.git",
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val resolvedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().repositoryIdentity != null && !repositories.single().isLoading
            }.single()
        }
        assertEquals(GitHubRepositoryIdentity("acme", "widgets"), resolvedRepository.repositoryIdentity)

        viewModel.updateConfig { config ->
            config.copy(
                localRepositories = listOf(
                    LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = listOf("direnv allow")),
                ),
            )
        }

        assertEquals(
            GitHubRepositoryIdentity("acme", "widgets"),
            viewModel.localRepositoriesStateFlow.value.single().repositoryIdentity,
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
                onListWorktreeEntries = {
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
        assertEquals(false, repository.isLoading)
        assertEquals(listOf("main", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.isDirty })
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })

        releaseEnrichment.complete(Unit)
        val enrichedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().operationRequest == null
            }.single()
        }
        val stackedWorktree = enrichedRepository.worktrees.single { it.branch == "feature/stacked-pr" }
        assertEquals("main", stackedWorktree.parentBranch)
        assertEquals(true, stackedWorktree.needsRebase)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, stackedWorktree.path)
        assertEquals(null, stackedWorktree.isDirty)
        assertEquals(false, stackedWorktree.isRoot)
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
                repositories.single().operationRequest == null && repositories.single().worktrees.size == 2
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
                repository.isExpanded && repository.operationRequest == null && repository.worktrees.size == 2
            }.single()
        }

        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, false), repository.worktrees.map { it.needsRebase })
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }
}

class EngHubLocalRepositoryFirstPaintViewModelTest {
    @Test
    fun expandingConfiguredRepositoryPublishesBranchRowsWithUnknownDirtyStatus() = runBlocking {
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
        assertEquals(listOf(null, null), repository.worktrees.map { it.isDirty })
        assertEquals(listOf(true, false), repository.worktrees.map { it.isRoot })
    }

    @Test
    fun expandingRepositoryPublishesBranchRowsWhileEnrichmentIsBlocked() = runBlocking {
        val enrichmentStarted = CompletableDeferred<Unit>()
        val releaseEnrichment = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                            isDirty = true,
                        ),
                    ),
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
        assertEquals(false, repository.isLoading)
        assertEquals(listOf("main", "feature/login"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.isDirty })

        releaseEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().operationRequest == null
            }
        }
        assertEquals(
            listOf("main", "feature/login"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
    }

    @Test
    fun addingRepositoryPublishesBranchRowsBeforeEnrichmentCompletes() = runBlocking {
        val enrichmentStarted = CompletableDeferred<Unit>()
        val releaseEnrichment = CompletableDeferred<Unit>()
        val repositoryWorktrees = RepositoryWorktrees(
            rootPath = DEV_LAKE_ROOT,
            selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
            worktrees = listOf(
                Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                Worktree(
                    path = DEV_LAKE_SELECTED_WORKTREE,
                    branch = "feature/login",
                    commitHash = "def456",
                    isDirty = true,
                ),
            ),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(DEV_LAKE_SELECTED_WORKTREE to repositoryWorktrees),
            responses = RecordingGitWorktreeApiResponses(
                originUrlsByRepoPath = mapOf(DEV_LAKE_ROOT to "git@github.com:acme/widgets.git"),
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
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { enrichmentStarted.await() }

        val repository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(true, repository.isExpanded)
        assertEquals(false, repository.isLoading)
        assertEquals(listOf("main", "feature/login"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, null), repository.worktrees.map { it.isDirty })
        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedEntryPaths)
        assertEquals(emptyList(), api.resolvedPaths)

        releaseEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val completedRepository = repositories.single()
                completedRepository.repositoryIdentity != null && completedRepository.refreshRequest == null
            }
        }
        assertEquals(
            GitHubRepositoryIdentity("acme", "widgets"),
            viewModel.localRepositoriesStateFlow.value.single().repositoryIdentity,
        )
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
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
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
        assertEquals(
            listOf(null, null),
            repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.isDirty },
        )
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
            testConfig = startedWorktreePollingConfig(intervalMs = 250),
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
        assertEquals(null, stackedWorktree.isDirty)
        assertEquals("main", stackedWorktree.parentBranch)
        assertEquals(true, stackedWorktree.needsRebase)

        releasePollEnrichment.complete(Unit)
        pollingJobs.forEach { job ->
            job.cancel()
            job.join()
        }
    }

    @Test
    fun refreshEnrichmentFailureRetainsMatchingMetadataAndAllowsNextRefresh() = runBlocking {
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
        val repository = withTimeout(2_000.milliseconds) {
            fixture.state.localRepositories.first { repositories ->
                val currentRepository = repositories.singleOrNull()
                val stackedWorktree = currentRepository?.worktrees?.singleOrNull { it.branch == "feature/stacked-pr" }
                currentRepository?.refreshRequest == null && stackedWorktree?.parentBranch == "main"
            }.single()
        }

        assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(listOf("main", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(listOf(null, "main"), repository.worktrees.map { it.parentBranch })
        assertEquals(listOf(false, true), repository.worktrees.map { it.needsRebase })
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
                    onListWorktreeEntries = { listCalls.tryReceive().getOrThrow().invoke() },
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
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
        val expansionEnrichmentLookupDone = CompletableDeferred<Unit>()
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
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to stackedPollWorktrees()),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "main")),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktreeEntries = { listCalls.tryReceive().getOrThrow().invoke() },
                onInferWorktreeParentBranches = {
                    expansionEnrichmentStarted.complete(Unit)
                    runBlocking { releaseExpansionEnrichment.await() }
                },
                onInferOriginDefaultBranch = { expansionEnrichmentLookupDone.complete(Unit) },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
        )
        val pollingJobs = pollingJobs(viewModel)

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { expansionEnrichmentStarted.await() }
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
        withTimeout(2_000.milliseconds) { expansionEnrichmentLookupDone.await() }
        // The invalidated expansion's discarded apply is the synchronous next step after its lookup.
        delay(100.milliseconds)
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
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
        )
        val pollingJobs = pollingJobs(viewModel)

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { expansionEnrichmentStarted.await() }
        val refreshedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees?.map { it.branch } == listOf("new-main")
            }.single()
        }
        assertEquals(null, refreshedRepository.operationRequest)

        releaseExpansionEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { refreshEnrichmentStarted.await() }
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
                onListWorktreeEntries = {
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
        val enrichmentLookupDone = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
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
                onInferOriginDefaultBranch = { enrichmentLookupDone.complete(Unit) },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { enrichmentStarted.await() }
        val discoveredWorktrees = viewModel.localRepositoriesStateFlow.value.single().worktrees
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        releaseEnrichment.complete(Unit)
        withTimeout(2_000.milliseconds) { enrichmentLookupDone.await() }
        // The collapsed expansion's discarded apply is the synchronous next step after its lookup.
        delay(100.milliseconds)

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

        releaseOldDiscovery.complete(Unit)
        withTimeout(2_000.milliseconds) { olderRefresh.join() }
        val refreshedRepository = withTimeout(2_000.milliseconds) {
            fixture.state.localRepositories.first { repositories ->
                repositories.single().worktrees.singleOrNull {
                    it.branch == "feature/stacked-pr"
                }?.parentBranch == "new-main"
            }.single()
        }

        assertNewRefreshWorktrees(refreshedRepository.worktrees)
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

        releaseOldEnrichment.complete(Unit)
        // The older refresh's enrichment is discarded before the queued newer enrichment runs, so
        // observing the newer enrichment proves the older one never replaced the newer rows.
        val refreshedRepository = withTimeout(2_000.milliseconds) {
            fixture.state.localRepositories.first { repositories ->
                repositories.single().worktrees.singleOrNull {
                    it.branch == "feature/stacked-pr"
                }?.parentBranch == "new-main"
            }.single()
        }

        assertNewRefreshWorktrees(refreshedRepository.worktrees)
    }

    @Test
    fun expansionStartedAfterRefreshPreventsStaleEnrichmentFromReplacingMetadata() = runBlocking {
        val refreshEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseRefreshEnrichment = CompletableDeferred<Unit>()
        val enrichmentLookupsDone = Channel<Unit>(Channel.UNLIMITED)
        val parentBranches = mutableMapOf<String, String>()
        val enrichmentCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                refreshEnrichmentStarted.complete(Unit)
                runBlocking { releaseRefreshEnrichment.await() }
                parentBranches["feature/stacked-pr"] = "main"
            }
            trySend { parentBranches.clear() }
        }
        val staleRebaseCall = BranchNeedsRebaseCall(DEV_LAKE_ROOT, "main", "feature/stacked-pr")
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to stackedPollWorktrees()),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to parentBranches),
                branchNeedsRebaseByCall = mapOf(staleRebaseCall to true),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferWorktreeParentBranches = {
                    enrichmentCalls.tryReceive().getOrNull()?.invoke()
                },
                onInferOriginDefaultBranch = { enrichmentLookupsDone.trySend(Unit) },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
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
        // Exactly two enrichments remain: the stale refresh's, then the newest queued one.
        repeat(2) {
            withTimeout(2_000.milliseconds) { enrichmentLookupsDone.receive() }
        }
        // Both enrichments' applies are the synchronous next steps after their lookups.
        delay(100.milliseconds)

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
                onListWorktreeEntries = { repoPath ->
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
                    onListWorktreeEntries = { listCalls.tryReceive().getOrThrow().invoke() },
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
                onListWorktreeEntries = {
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

class EngHubLocalWorktreeStatusHydrationViewModelTest {
    @Test
    fun expandingRepositoryHydratesDirtyStatusAfterPublishingUnknownRows() = runBlocking {
        val statusStarted = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                        ),
                    ),
                ),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/login" to "main"),
                ),
                isDirtyForWorktreePath = { worktreePath ->
                    when (worktreePath) {
                        DEV_LAKE_ROOT -> false

                        DEV_LAKE_SELECTED_WORKTREE -> {
                            statusStarted.complete(Unit)
                            runBlocking { releaseStatus.await() }
                            true
                        }

                        else -> error("Unexpected worktree path $worktreePath")
                    }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { statusStarted.await() }

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees?.size == 2 && !repositories.single().isLoading
            }.single()
        }
        assertEquals(null, repository.worktrees.single { it.branch == "feature/login" }.isDirty)
        assertEquals(null, viewModel.actionErrorStateFlow.value)

        releaseStatus.complete(Unit)
        val hydratedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.singleOrNull { it.branch == "feature/login" }?.isDirty == true
            }.single()
        }

        assertEquals(false, hydratedRepository.isLoading)
        assertEquals(
            listOf(false, true),
            hydratedRepository.worktrees.map { it.isDirty },
        )
        assertEquals("main", hydratedRepository.worktrees.single { it.branch == "feature/login" }.parentBranch)
    }

    @Test
    fun failingStatusCheckHydratesSiblingRowAndLeavesFailedRowUnknown() = runBlocking {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                        ),
                    ),
                ),
                isDirtyForWorktreePath = { worktreePath ->
                    when (worktreePath) {
                        DEV_LAKE_ROOT -> error("status check failed for $worktreePath")
                        DEV_LAKE_SELECTED_WORKTREE -> true
                        else -> error("Unexpected worktree path $worktreePath")
                    }
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
            viewModel.localRepositoriesStateFlow.first { repositories ->
                api.worktreeIsDirtyCalls.containsAll(listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)) &&
                    repositories.singleOrNull()?.isLoading == false
            }
        }
        val hydratedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees
                    ?.singleOrNull { it.branch == "feature/login" }?.isDirty == true
            }.single()
        }

        assertEquals(null, hydratedRepository.worktrees.single { it.branch == "main" }.isDirty)
        assertEquals(
            listOf(null, true),
            hydratedRepository.worktrees.map { it.isDirty },
        )
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun addingRepositoryHydratesDirtyStatusAfterPublishingUnknownRows() = runBlocking {
        val statusStarted = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(
                DEV_LAKE_SELECTED_WORKTREE to RepositoryWorktrees(
                    rootPath = DEV_LAKE_ROOT,
                    selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                    worktrees = listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                        ),
                    ),
                ),
            ),
            responses = RecordingGitWorktreeApiResponses(
                isDirtyForWorktreePath = { worktreePath ->
                    when (worktreePath) {
                        DEV_LAKE_ROOT -> false

                        DEV_LAKE_SELECTED_WORKTREE -> {
                            statusStarted.complete(Unit)
                            runBlocking { releaseStatus.await() }
                            true
                        }

                        else -> error("Unexpected worktree path $worktreePath")
                    }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { statusStarted.await() }

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees?.size == 2 && !repositories.single().isLoading
            }.single()
        }
        assertEquals(null, repository.worktrees.single { it.branch == "feature/login" }.isDirty)

        releaseStatus.complete(Unit)
        val hydratedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.singleOrNull { it.branch == "feature/login" }?.isDirty == true
            }.single()
        }

        assertEquals(false, hydratedRepository.isLoading)
        assertEquals(
            listOf(false, true),
            hydratedRepository.worktrees.map { it.isDirty },
        )
    }

    @Test
    fun unchangedRefreshAllowsSlowStatusToCompleteBeforeStartingAnotherCheck() = runBlocking {
        val firstStatusStarted = CompletableDeferred<Unit>()
        val releaseFirstStatus = CompletableDeferred<Unit>()
        val secondStatusStarted = CompletableDeferred<Unit>()
        val releaseSecondStatus = CompletableDeferred<Unit>()
        val api = gatedRefreshStatusApi(
            firstStatusStarted = firstStatusStarted,
            releaseFirstStatus = releaseFirstStatus,
            secondStatusStarted = secondStatusStarted,
            releaseSecondStatus = releaseSecondStatus,
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = startedWorktreePollingConfig(intervalMs = 500),
        )
        val pollingJobs = pollingJobs(viewModel)

        withTimeout(2_000.milliseconds) { firstStatusStarted.await() }
        val firstRefreshRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.singleOrNull()?.worktrees?.size == 1
            }.single()
        }
        assertEquals(null, firstRefreshRepository.worktrees.single().isDirty)

        val secondRefreshRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                api.listWorktreeRepoPaths.size >= 2 && repositories.singleOrNull()?.worktrees?.size == 1
            }.single()
        }
        assertEquals(null, secondRefreshRepository.worktrees.single().isDirty)

        releaseFirstStatus.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.singleOrNull()?.isDirty == true
            }
        }
        withTimeout(2_000.milliseconds) { secondStatusStarted.await() }
        releaseSecondStatus.complete(Unit)
        val hydratedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.singleOrNull()?.isDirty == false
            }.single()
        }
        assertEquals(listOf("feature/login"), hydratedRepository.worktrees.map { it.branch })

        cancelJobs(pollingJobs)
    }
}

class EngHubLocalRepositoryOfflineMetadataViewModelTest {
    private fun assertRetainedHierarchy(feature: LocalWorktreeUiState) {
        assertEquals("main", feature.parentBranch)
        assertEquals("main", feature.integrationTargetBranch)
        assertEquals(true, feature.needsRebase)
    }

    @Test
    fun unresolvedOriginRetainsMetadataOnlyForMatchingPathAndBranch() = runBlocking {
        val featurePath = "$DEV_LAKE_ROOT-feature"
        val replacedPath = "$DEV_LAKE_ROOT-replaced"
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc"),
                        Worktree(path = featurePath, branch = "feature/login", commitHash = "def"),
                        Worktree(path = replacedPath, branch = "feature/logout", commitHash = "ghi"),
                        Worktree(path = "$featurePath-new", branch = "feature/login", commitHash = "jkl"),
                    ),
                ),
                originDefaultBranchFailure = IllegalStateException("offline"),
                unresolvedParentBranches = setOf("feature/login"),
                isDirtyForWorktreePath = { _ -> true },
            ),
        )
        val fixture = createRefreshControllerFixture(api)
        fixture.state.localRepositories.value = fixture.state.localRepositories.value.map { repository ->
            repository.copy(
                worktrees = listOf(
                    LocalWorktreeUiState(
                        branch = "main",
                        path = DEV_LAKE_ROOT,
                        canUpdateFromOrigin = true,
                    ),
                    LocalWorktreeUiState(
                        branch = "feature/login",
                        path = featurePath,
                        parentBranch = "main",
                        needsRebase = true,
                        integrationTargetBranch = "main",
                    ),
                    LocalWorktreeUiState(
                        branch = "feature/old",
                        path = replacedPath,
                        integrationTargetBranch = "main",
                    ),
                ),
            )
        }

        fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "offline refresh")
        val repository = withTimeout(2_000.milliseconds) {
            fixture.state.localRepositories.first { repositories ->
                val current = repositories.single()
                current.refreshRequest == null && current.worktrees.all { it.isDirty == true }
            }.single()
        }
        assertEquals(true, repository.worktrees.single { it.path == DEV_LAKE_ROOT }.canUpdateFromOrigin)
        assertRetainedHierarchy(repository.worktrees.single { it.path == featurePath })
        repository.worktrees.filter { it.path == replacedPath || it.path == "$featurePath-new" }.forEach {
            assertEquals(null, it.integrationTargetBranch)
            assertEquals(null, it.parentBranch)
            assertEquals(false, it.canUpdateFromOrigin)
        }
    }
}

class EngHubLocalRepositoryRemoteLookupViewModelTest {
    @Test
    fun pollPublishesRowsWithLocalStatusWhileServerDefaultBranchLookupBlocks() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                        ),
                    ),
                ),
                isDirtyForWorktreePath = { _ -> false },
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    lookupStarted.complete(Unit)
                    runBlocking { releaseLookup.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
        )

        withTimeout(2_000.milliseconds) { lookupStarted.await() }
        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val currentRepository = repositories.singleOrNull()
                currentRepository != null &&
                    !currentRepository.isLoading &&
                    currentRepository.worktrees.map { it.isDirty } == listOf(false, false)
            }.single()
        }
        assertEquals(listOf("main", "feature/login"), repository.worktrees.map { it.branch })
        assertEquals(listOf(true, false), repository.worktrees.map { it.isRoot })

        // Polling keeps refreshing local rows while the server lookup blocks...
        withTimeout(2_000.milliseconds) {
            while (api.listWorktreeRepoPaths.size < 3) delay(1.milliseconds)
        }
        // ...without starting lookups behind the blocked one.
        assertEquals(listOf(DEV_LAKE_ROOT), api.inferOriginDefaultBranchRepoPaths)

        releaseLookup.complete(Unit)
        val enrichedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.single { it.branch == "main" }.canUpdateFromOrigin == true
            }.single()
        }
        cancelJobs(pollingJobs(viewModel))
        assertEquals(true, enrichedRepository.worktrees.single { it.branch == "main" }.canUpdateFromOrigin)
        assertEquals(false, enrichedRepository.worktrees.single { it.branch == "feature/login" }.canUpdateFromOrigin)
    }

    @Test
    fun expansionPublishesRowsWithLocalStatusWhileServerDefaultBranchLookupBlocks() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                        ),
                    ),
                ),
                isDirtyForWorktreePath = { _ -> true },
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    lookupStarted.complete(Unit)
                    runBlocking { releaseLookup.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { lookupStarted.await() }

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val currentRepository = repositories.singleOrNull()
                currentRepository != null &&
                    !currentRepository.isLoading &&
                    currentRepository.worktrees.map { it.isDirty } == listOf(true, true)
            }.single()
        }
        assertEquals(listOf("main", "feature/login"), repository.worktrees.map { it.branch })
        assertEquals(true, repository.isExpanded)
        val featureBefore = repository.worktrees.single { it.branch == "feature/login" }
        assertEquals(null, featureBefore.integrationTargetBranch)

        releaseLookup.complete(Unit)
        val enrichedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.single { it.branch == "main" }.canUpdateFromOrigin == true
            }.single()
        }
        assertEquals(true, enrichedRepository.worktrees.single { it.branch == "main" }.canUpdateFromOrigin)
        val featureAfter = enrichedRepository.worktrees.single { it.branch == "feature/login" }
        assertEquals(false, featureAfter.canUpdateFromOrigin)
        assertEquals("main", featureAfter.integrationTargetBranch)
        assertEquals(featureBefore.copy(integrationTargetBranch = "main"), featureAfter)
        assertEquals(listOf(true, true), enrichedRepository.worktrees.map { it.isDirty })
    }

    @Test
    fun addRepositoryPublishesRowsWithLocalStatusWhileServerDefaultBranchLookupBlocks() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(
                DEV_LAKE_SELECTED_WORKTREE to RepositoryWorktrees(
                    rootPath = DEV_LAKE_ROOT,
                    selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                    worktrees = listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/login",
                            commitHash = "def456",
                        ),
                    ),
                ),
            ),
            responses = RecordingGitWorktreeApiResponses(
                isDirtyForWorktreePath = { _ -> false },
                originUrlsByRepoPath = mapOf(DEV_LAKE_ROOT to "git@github.com:acme/widgets.git"),
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    lookupStarted.complete(Unit)
                    runBlocking { releaseLookup.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { lookupStarted.await() }

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val currentRepository = repositories.singleOrNull()
                currentRepository != null &&
                    !currentRepository.isLoading &&
                    currentRepository.worktrees.map { it.isDirty } == listOf(false, false) &&
                    currentRepository.repositoryIdentity != null
            }.single()
        }
        assertEquals(listOf("main", "feature/login"), repository.worktrees.map { it.branch })
        assertEquals(GitHubRepositoryIdentity("acme", "widgets"), repository.repositoryIdentity)

        releaseLookup.complete(Unit)
        val enrichedRepository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.single { it.branch == "main" }.canUpdateFromOrigin == true
            }.single()
        }
        assertEquals(true, enrichedRepository.worktrees.single { it.branch == "main" }.canUpdateFromOrigin)
        assertEquals(
            GitHubRepositoryIdentity("acme", "widgets"),
            enrichedRepository.repositoryIdentity,
        )
    }

    @Test
    fun bestEffortRefreshCompletesWhileServerDefaultBranchLookupBlocks() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")),
                ),
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    lookupStarted.complete(Unit)
                    runBlocking { releaseLookup.await() }
                },
            ),
        )
        val fixture = createRefreshControllerFixture(api)

        val refresh = launch(Dispatchers.IO) {
            fixture.controller.refreshLocalRepositoryWorktreesBestEffort(DEV_LAKE_ROOT, "test refresh")
        }
        withTimeout(2_000.milliseconds) { lookupStarted.await() }
        withTimeout(2_000.milliseconds) { refresh.join() }

        val repository = fixture.state.localRepositories.value.single()
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(false, repository.isLoading)

        releaseLookup.complete(Unit)
        val refreshedRepository = withTimeout(2_000.milliseconds) {
            fixture.state.localRepositories.first { repositories ->
                repositories.single().worktrees.single { it.branch == "main" }.canUpdateFromOrigin == true
            }.single()
        }
        assertEquals(true, refreshedRepository.worktrees.single { it.branch == "main" }.canUpdateFromOrigin)
    }

    @Test
    fun pollRefreshesSecondRepositoryWhileFirstServerLookupBlocks() = runBlocking {
        val apiLookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")),
                    DOCS_ROOT to listOf(Worktree(path = DOCS_ROOT, branch = "docs-main", commitHash = "123abc")),
                ),
                originDefaultBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to "main",
                    DOCS_ROOT to "docs-main",
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = { repoPath ->
                    if (repoPath == DEV_LAKE_ROOT) {
                        apiLookupStarted.complete(Unit)
                        runBlocking { releaseLookup.await() }
                    }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT, DOCS_ROOT),
            testConfig = startedWorktreePollingConfig(intervalMs = 25),
        )

        withTimeout(2_000.milliseconds) { apiLookupStarted.await() }
        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { candidates ->
                candidates.single { it.path == DOCS_ROOT }.worktrees.isNotEmpty() &&
                    candidates.single { it.path == DEV_LAKE_ROOT }.worktrees.isNotEmpty()
            }
        }
        assertEquals(listOf("docs-main"), repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch })
        assertEquals(false, repositories.single { it.path == DOCS_ROOT }.isLoading)

        releaseLookup.complete(Unit)
        cancelJobs(pollingJobs(viewModel))
    }
}

/**
 * Serves one `feature/login` worktree whose first two status checks block on separate gates and return dirty;
 * later checks block on the second gate and report clean.
 */
private fun gatedRefreshStatusApi(
    firstStatusStarted: CompletableDeferred<Unit>,
    releaseFirstStatus: CompletableDeferred<Unit>,
    secondStatusStarted: CompletableDeferred<Unit>,
    releaseSecondStatus: CompletableDeferred<Unit>,
): RecordingGitWorktreeApi {
    val statusGates = Channel<CompletableDeferred<Unit>>(capacity = 2).apply {
        trySend(releaseFirstStatus)
        trySend(releaseSecondStatus)
    }
    return RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(
                    Worktree(
                        path = DEV_LAKE_SELECTED_WORKTREE,
                        branch = "feature/login",
                        commitHash = "abc123",
                    ),
                ),
            ),
            isDirtyForWorktreePath = { _ ->
                val release = statusGates.tryReceive().getOrNull() ?: releaseSecondStatus
                if (release === releaseFirstStatus) {
                    firstStatusStarted.complete(Unit)
                } else {
                    secondStatusStarted.complete(Unit)
                }
                runBlocking { release.await() }
                release === releaseFirstStatus
            },
        ),
    )
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
    assertEquals(null, stackedWorktree.isDirty)
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

private fun startedWorktreePollingConfig(intervalMs: Long) = LocalRepositoryViewModelTestConfig(
    worktreePollIntervalMs = intervalMs,
    startConfiguredRepositoryPolling = true,
)
