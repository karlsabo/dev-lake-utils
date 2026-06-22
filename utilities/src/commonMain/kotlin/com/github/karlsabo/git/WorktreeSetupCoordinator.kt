package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorktreeSetupRequest(
    val repoPath: String,
    val worktreePath: WorktreePath,
    val cloneUrl: String? = null,
    val branch: String? = null,
    val baseWorktreePath: String? = null,
    val baseBranch: String? = null,
    val baseCommitIsh: String? = null,
    val targetBranch: String? = null,
    val allowUnrelatedExistingBranch: Boolean = false,
    val setupShell: String = "",
    val setupCommands: List<String> = emptyList(),
) {
    init {
        require(repoPath.isNotBlank()) { "repoPath must not be blank" }
        require((cloneUrl == null) == (branch == null)) {
            "cloneUrl and branch must both be provided for repository/worktree setup, " +
                "or both omitted for an existing worktree"
        }
        val hasBranchCreationFields = baseWorktreePath != null ||
            baseBranch != null ||
            baseCommitIsh != null ||
            targetBranch != null
        val hasCompleteBranchCreationFields = baseWorktreePath != null &&
            targetBranch != null &&
            listOfNotNull(baseBranch, baseCommitIsh).size == 1
        require(!hasBranchCreationFields || hasCompleteBranchCreationFields) {
            "baseWorktreePath, targetBranch, and exactly one of baseBranch or baseCommitIsh must be provided " +
                "for branch worktree creation, or all omitted"
        }
        require(cloneUrl == null || !hasBranchCreationFields) {
            "repository/worktree setup and branch worktree creation are mutually exclusive"
        }
        val hasBranchBasedCreationFields = baseWorktreePath != null && baseBranch != null && targetBranch != null
        require(!allowUnrelatedExistingBranch || hasBranchBasedCreationFields) {
            "allowUnrelatedExistingBranch requires branch-based worktree creation fields"
        }
        cloneUrl?.let { require(it.isNotBlank()) { "cloneUrl must not be blank" } }
        branch?.let { require(it.isNotBlank()) { "branch must not be blank" } }
        baseWorktreePath?.let { require(it.isNotBlank()) { "baseWorktreePath must not be blank" } }
        baseBranch?.let { require(it.isNotBlank()) { "baseBranch must not be blank" } }
        baseCommitIsh?.let { require(it.isNotBlank()) { "baseCommitIsh must not be blank" } }
        targetBranch?.let { require(it.isNotBlank()) { "targetBranch must not be blank" } }
        if (setupCommands.isNotEmpty()) {
            require(setupShell.isNotBlank()) { "setupShell must not be blank when setupCommands are provided" }
        }
    }

    internal val shouldEnsureRepositoryAndWorktree: Boolean = cloneUrl != null
    internal val shouldCreateBranchWorktree: Boolean = baseWorktreePath != null
    internal val shouldCreateBranchWorktreeFromCommitIsh: Boolean = baseCommitIsh != null
}

data class WorktreeSetupResult(
    val worktreePath: WorktreePath,
    val setupCommandResult: WorktreeSetupCommandResult? = null,
)

data class WorktreeSetupCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

enum class WorktreeSetupStatus {
    WAITING_FOR_REPOSITORY,
    CREATING_OR_REUSING_WORKTREE,
    RUNNING_SETUP_COMMANDS,
}

class WorktreeSetupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun interface WorktreeSetupCommandRunner {
    suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult
}

class ShellWorktreeSetupCommandRunner : WorktreeSetupCommandRunner {
    override suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult {
        if (request.setupCommands.isEmpty()) {
            return WorktreeSetupCommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        val result = executeCommand(
            command = listOf(request.setupShell, "-l", "-c", buildWorktreeSetupScript(request)),
            workingDirectory = request.worktreePath.value,
        )
        if (result.exitCode != 0) {
            val output = listOf(result.stderr, result.stdout)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            throw WorktreeSetupException(
                "Setup commands failed for ${request.worktreePath.value} with exit code ${result.exitCode}: $output",
            )
        }
        return WorktreeSetupCommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
        )
    }
}

interface WorktreeSetupHandle {
    val isCompleted: Boolean

    suspend fun await(): WorktreeSetupResult
}

private class ReadOnlyWorktreeSetupHandle(
    private val deferred: Deferred<WorktreeSetupResult>,
) : WorktreeSetupHandle {
    override val isCompleted: Boolean
        get() = deferred.isCompleted

    override suspend fun await(): WorktreeSetupResult = deferred.await()
}

private class WorktreeSetupOperation {
    val result = CompletableDeferred<WorktreeSetupResult>()
    val handle: WorktreeSetupHandle = ReadOnlyWorktreeSetupHandle(result)
}

private class WorktreeSetupCompletionObserver(
    val beforeResultCompletion: suspend (WorktreeSetupRequest) -> Unit,
)

class WorktreeSetupCoordinator private constructor(
    private val gitWorktreeApi: GitWorktreeApi,
    private val setupCommandRunner: WorktreeSetupCommandRunner,
    private val scope: CoroutineScope,
    private val completionObserver: WorktreeSetupCompletionObserver,
) {
    constructor(
        gitWorktreeApi: GitWorktreeApi = GitWorktreeService(),
        setupCommandRunner: WorktreeSetupCommandRunner = ShellWorktreeSetupCommandRunner(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(gitWorktreeApi, setupCommandRunner, scope, WorktreeSetupCompletionObserver {})

    internal constructor(
        gitWorktreeApi: GitWorktreeApi = GitWorktreeService(),
        setupCommandRunner: WorktreeSetupCommandRunner = ShellWorktreeSetupCommandRunner(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        beforeResultCompletion: suspend (WorktreeSetupRequest) -> Unit,
    ) : this(gitWorktreeApi, setupCommandRunner, scope, WorktreeSetupCompletionObserver(beforeResultCompletion))

    private val inFlightOperations = MutableStateFlow<Map<WorktreePath, WorktreeSetupOperation>>(emptyMap())
    private val repositoryMutexes = mutableMapOf<String, Mutex>()
    private val repositoryMutexesGuard = Mutex()
    private val _statuses = MutableStateFlow<Map<WorktreePath, WorktreeSetupStatus>>(emptyMap())

    val statuses: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> = _statuses.asStateFlow()

    fun setup(request: WorktreeSetupRequest): WorktreeSetupHandle {
        while (true) {
            val currentOperations = inFlightOperations.value
            currentOperations[request.worktreePath]?.let { return it.handle }

            val operation = WorktreeSetupOperation()
            if (inFlightOperations.compareAndSet(
                    expect = currentOperations,
                    update = currentOperations + (request.worktreePath to operation),
                )
            ) {
                request.initialStatus()?.let { setStatus(request.worktreePath, it) }
                startSetup(request, operation)
                return operation.handle
            }
        }
    }

    private fun startSetup(
        request: WorktreeSetupRequest,
        operation: WorktreeSetupOperation,
    ) {
        scope.launch {
            val completion = runCatching { performSetup(request) }
            _statuses.update { it - request.worktreePath }
            val observedCompletion = runCatching {
                completionObserver.beforeResultCompletion(request)
                completion.getOrThrow()
            }
            observedCompletion.fold(
                onSuccess = { operation.result.complete(it) },
                onFailure = { operation.result.completeExceptionally(it) },
            )
            inFlightOperations.update { it - request.worktreePath }
        }
    }

    private suspend fun performSetup(request: WorktreeSetupRequest): WorktreeSetupResult {
        if (request.shouldEnsureRepositoryAndWorktree) {
            val cloneUrl = requireNotNull(request.cloneUrl)
            val branch = requireNotNull(request.branch)

            setStatus(request.worktreePath, WorktreeSetupStatus.WAITING_FOR_REPOSITORY)
            repositoryMutexFor(request.repoPath).withLock {
                gitWorktreeApi.ensureRepository(request.repoPath, cloneUrl)
            }

            setStatus(request.worktreePath, WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE)
            val ensuredPath = WorktreePath(gitWorktreeApi.ensureWorktree(request.repoPath, branch))
            if (ensuredPath != request.worktreePath) {
                throw WorktreeSetupException(
                    "ensureWorktree returned $ensuredPath, but request expected ${request.worktreePath}",
                )
            }
        } else if (request.shouldCreateBranchWorktree) {
            setStatus(request.worktreePath, WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE)
            val createdPath = WorktreePath(
                if (request.shouldCreateBranchWorktreeFromCommitIsh) {
                    gitWorktreeApi.createBranchWorktreeFromCommitIsh(
                        repoPath = request.repoPath,
                        baseWorktreePath = requireNotNull(request.baseWorktreePath),
                        baseCommitIsh = requireNotNull(request.baseCommitIsh),
                        targetBranch = requireNotNull(request.targetBranch),
                    )
                } else {
                    gitWorktreeApi.createBranchWorktree(
                        repoPath = request.repoPath,
                        baseWorktreePath = requireNotNull(request.baseWorktreePath),
                        baseBranch = requireNotNull(request.baseBranch),
                        targetBranch = requireNotNull(request.targetBranch),
                        allowUnrelatedExistingBranch = request.allowUnrelatedExistingBranch,
                    )
                },
            )
            if (createdPath != request.worktreePath) {
                throw WorktreeSetupException(
                    "createBranchWorktree returned $createdPath, but request expected ${request.worktreePath}",
                )
            }
        }

        val setupCommandResult = if (request.setupCommands.isEmpty()) {
            null
        } else {
            setStatus(request.worktreePath, WorktreeSetupStatus.RUNNING_SETUP_COMMANDS)
            setupCommandRunner.runSetup(request)
        }

        return WorktreeSetupResult(
            worktreePath = request.worktreePath,
            setupCommandResult = setupCommandResult,
        )
    }

    private suspend fun repositoryMutexFor(repoPath: String): Mutex = repositoryMutexesGuard.withLock {
        repositoryMutexes.getOrPut(repoPath) { Mutex() }
    }

    private fun WorktreeSetupRequest.initialStatus(): WorktreeSetupStatus? = when {
        shouldEnsureRepositoryAndWorktree -> WorktreeSetupStatus.WAITING_FOR_REPOSITORY
        shouldCreateBranchWorktree -> WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE
        setupCommands.isNotEmpty() -> WorktreeSetupStatus.RUNNING_SETUP_COMMANDS
        else -> null
    }

    private fun setStatus(worktreePath: WorktreePath, status: WorktreeSetupStatus) {
        _statuses.update { it + (worktreePath to status) }
    }
}

fun buildWorktreeSetupScript(request: WorktreeSetupRequest): String = buildWorktreeSetupScript(
    request.setupCommands.map { command -> command.expandWorktreeSetupPlaceholders(request) },
)

fun buildWorktreeSetupScript(commands: List<String>): String = buildString {
    appendLine("setup_exit_code=0")
    commands.forEach { command ->
        appendLine(command)
        appendLine("command_exit_code=$?")
        appendLine(
            $$"if [ \"$command_exit_code\" -ne 0 ] && [ \"$setup_exit_code\" -eq 0 ]; then " +
                $$"setup_exit_code=\"$command_exit_code\"; fi",
        )
    }
    append($$"exit \"$setup_exit_code\"")
}

private fun String.expandWorktreeSetupPlaceholders(request: WorktreeSetupRequest): String = expandShellPlaceholders(
    mapOf(
        $$"$root-repo-dir" to request.repoPath,
        $$"$worktree-dir" to request.worktreePath.value,
    ),
)

private enum class ShellQuoteContext {
    UNQUOTED,
    SINGLE_QUOTED,
    DOUBLE_QUOTED,
}

private fun String.expandShellPlaceholders(replacements: Map<String, String>): String {
    val expanded = StringBuilder()
    var quoteContext = ShellQuoteContext.UNQUOTED
    var index = 0

    while (index < length) {
        val replacement = replacements.entries.firstOrNull { (placeholder, _) -> startsWith(placeholder, index) }
        if (replacement != null) {
            expanded.append(replacement.value.escapeForShellContext(quoteContext))
            index += replacement.key.length
            continue
        }

        val char = this[index]
        expanded.append(char)
        when (quoteContext) {
            ShellQuoteContext.UNQUOTED -> when (char) {
                '\'' -> quoteContext = ShellQuoteContext.SINGLE_QUOTED
                '"' -> quoteContext = ShellQuoteContext.DOUBLE_QUOTED
                '\\' -> {
                    index += 1
                    if (index < length) expanded.append(this[index])
                }
            }
            ShellQuoteContext.SINGLE_QUOTED -> if (char == '\'') {
                quoteContext = ShellQuoteContext.UNQUOTED
            }
            ShellQuoteContext.DOUBLE_QUOTED -> when (char) {
                '"' -> quoteContext = ShellQuoteContext.UNQUOTED
                '\\' -> {
                    index += 1
                    if (index < length) expanded.append(this[index])
                }
            }
        }
        index += 1
    }

    return expanded.toString()
}

private fun String.escapeForShellContext(context: ShellQuoteContext): String = when (context) {
    ShellQuoteContext.UNQUOTED -> shellQuote()
    ShellQuoteContext.SINGLE_QUOTED -> escapeForSingleQuotedShell()
    ShellQuoteContext.DOUBLE_QUOTED -> escapeForDoubleQuotedShell()
}

private fun String.shellQuote(): String = "'${escapeForSingleQuotedShell()}'"

private fun String.escapeForSingleQuotedShell(): String = replace("'", "'\\''")

private fun String.escapeForDoubleQuotedShell(): String = buildString(length) {
    this@escapeForDoubleQuotedShell.forEach { char ->
        when (char) {
            '$', '`', '"', '\\' -> append('\\')
        }
        append(char)
    }
}
