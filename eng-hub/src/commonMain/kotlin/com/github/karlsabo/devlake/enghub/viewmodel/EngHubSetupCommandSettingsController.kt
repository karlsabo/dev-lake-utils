package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val SETUP_COMMAND_BLANK_ERROR = "Enter a setup command"

internal class EngHubSetupCommandSettingsController(
    coroutineScope: CoroutineScope,
    operationTracker: EngHubSettingsOperationTracker,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val configPersistence: EngHubConfigSettingsPersistence,
    private val pathEditor: EngHubLocalRepositoryPathEditor,
) {
    private val editQueue = SetupCommandEditQueue(
        coroutineScope,
        operationTracker,
        configPersistence,
        pathEditor.prepareForIndexedRepositoryOperation,
    )

    fun updateDraft(repositoryIndex: Int, command: String) {
        mutableUiState.updateRepositoryUiState(repositoryIndex) { repository ->
            repository.copy(setupCommandDraft = command, setupCommandError = null)
        }
    }

    fun add(repositoryIndex: Int, insertionIndex: Int) {
        val repository = mutableUiState.value.localRepositories.getOrNull(repositoryIndex)
        val repositoryId = pathEditor.repositoryOperationTarget(repositoryIndex)
        if (repository == null || repositoryId == null || insertionIndex !in 0..repository.setupCommands.size) return

        val command = repository.setupCommandDraft
        if (command.isBlank()) {
            mutableUiState.updateRepositoryUiState(repositoryIndex) {
                it.copy(setupCommandError = SETUP_COMMAND_BLANK_ERROR)
            }
            return
        }

        rebaseForCommandInsertion(repositoryIndex, insertionIndex)
        mutableUiState.updateRepositoryUiState(repositoryIndex) {
            it.copy(
                setupCommands = it.setupCommands.withInserted(insertionIndex, command),
                setupCommandDraft = "",
                setupCommandError = null,
            )
        }
        val operationKey = configPersistence.newOperationKey("setup-command-add")
        editQueue.launchOperation {
            val persistedPath = pathEditor.prepareForRepositoryOperation(repositoryId)
            configPersistence.update(operationKey) { currentConfig ->
                currentConfig.withSetupCommandInserted(
                    repositoryIndex = repositoryIndex,
                    expectedRepositoryPath = persistedPath,
                    insertionIndex = insertionIndex,
                    command = command,
                )
            }
        }
    }

    fun update(
        repositoryIndex: Int,
        commandIndex: Int,
        command: String,
    ) {
        val repository = mutableUiState.value.localRepositories.getOrNull(repositoryIndex)
        if (repository != null && commandIndex in repository.setupCommands.indices) {
            updateExistingCommand(repositoryIndex, commandIndex, command)
        }
    }

    fun remove(repositoryIndex: Int, commandIndex: Int) {
        val repository = mutableUiState.value.localRepositories.getOrNull(repositoryIndex)
        val repositoryId = pathEditor.repositoryOperationTarget(repositoryIndex)
        if (repository == null || repositoryId == null || commandIndex !in repository.setupCommands.indices) return

        rebaseForCommandRemoval(repositoryIndex, commandIndex)
        mutableUiState.updateRepositoryUiState(repositoryIndex) {
            it.copy(
                setupCommands = it.setupCommands.withRemovedAt(commandIndex),
                setupCommandEditErrors = it.setupCommandEditErrors.withoutIndex(commandIndex),
            )
        }
        val operationKey = configPersistence.newOperationKey("setup-command-remove")
        editQueue.launchOperation {
            val persistedPath = pathEditor.prepareForRepositoryOperation(repositoryId)
            configPersistence.update(operationKey) { currentConfig ->
                currentConfig.withSetupCommandRemoved(
                    repositoryIndex = repositoryIndex,
                    expectedRepositoryPath = persistedPath,
                    commandIndex = commandIndex,
                )
            }
        }
    }

    fun onRepositoryRemoved(repositoryIndex: Int) {
        val edits = editQueue.edits
        edits.forEach(editQueue::cancel)
        edits.forEach { edit ->
            when {
                edit.key.repositoryIndex == repositoryIndex -> editQueue.discard(edit)

                edit.key.repositoryIndex > repositoryIndex -> {
                    editQueue.schedule(edit.withRepositoryIndex(edit.key.repositoryIndex - 1))
                }

                else -> editQueue.schedule(edit)
            }
        }
    }

    fun onRepositoryInserted(repositoryIndex: Int) {
        val edits = editQueue.edits
        edits.forEach(editQueue::cancel)
        edits.forEach { edit ->
            val shiftedEdit = if (edit.key.repositoryIndex >= repositoryIndex) {
                edit.withRepositoryIndex(edit.key.repositoryIndex + 1)
            } else {
                edit
            }
            editQueue.schedule(shiftedEdit)
        }
    }

    suspend fun flushPendingEdits() {
        editQueue.flush()
    }

    private fun updateExistingCommand(
        repositoryIndex: Int,
        commandIndex: Int,
        command: String,
    ) {
        val key = SetupCommandEditKey(repositoryIndex, commandIndex)
        val existingEdit = editQueue.edit(key)
        existingEdit?.let(editQueue::cancel)
        mutableUiState.updateCommandDraft(repositoryIndex, commandIndex, command)
        if (command.isBlank()) {
            existingEdit?.let(editQueue::discard)
        } else {
            editQueue.schedule(
                PendingSetupCommandEdit(
                    key = key,
                    persistenceKey = existingEdit?.persistenceKey
                        ?: configPersistence.newOperationKey("setup-command-edit"),
                    replacementCommand = command,
                ),
            )
        }
    }

    private fun rebaseForCommandInsertion(repositoryIndex: Int, insertionIndex: Int) {
        val edits = editQueue.edits.filter { edit -> edit.key.repositoryIndex == repositoryIndex }
        edits.forEach(editQueue::cancel)
        edits.forEach { edit ->
            val shiftedIndex = if (edit.key.commandIndex >= insertionIndex) {
                edit.key.commandIndex + 1
            } else {
                edit.key.commandIndex
            }
            editQueue.schedule(edit.withCommandIndex(shiftedIndex))
        }
    }

    private fun rebaseForCommandRemoval(repositoryIndex: Int, commandIndex: Int) {
        val edits = editQueue.edits.filter { edit -> edit.key.repositoryIndex == repositoryIndex }
        edits.forEach(editQueue::cancel)
        edits.forEach { edit ->
            when {
                edit.key.commandIndex == commandIndex -> editQueue.discard(edit)

                edit.key.commandIndex > commandIndex -> {
                    editQueue.schedule(edit.withCommandIndex(edit.key.commandIndex - 1))
                }

                else -> editQueue.schedule(edit)
            }
        }
    }
}

private class SetupCommandEditQueue(
    private val coroutineScope: CoroutineScope,
    private val operationTracker: EngHubSettingsOperationTracker,
    private val configPersistence: EngHubConfigSettingsPersistence,
    private val prepareRepositoryPath: suspend (Int) -> String?,
) {
    private val commitJobs = mutableMapOf<SetupCommandEditKey, Job>()
    private val pendingEdits = mutableMapOf<SetupCommandEditKey, PendingSetupCommandEdit>()

    val edits: List<PendingSetupCommandEdit>
        get() = pendingEdits.values.toList()

    fun edit(key: SetupCommandEditKey): PendingSetupCommandEdit? = pendingEdits[key]

    fun launchOperation(operation: suspend CoroutineScope.() -> Unit) {
        operationTracker.launch(operation)
    }

    fun cancel(edit: PendingSetupCommandEdit) {
        commitJobs.remove(edit.key)?.cancel()
        pendingEdits.remove(edit.key)
    }

    fun discard(edit: PendingSetupCommandEdit) {
        cancel(edit)
        operationTracker.launch { configPersistence.discard(edit.persistenceKey) }
    }

    fun schedule(edit: PendingSetupCommandEdit) {
        pendingEdits[edit.key] = edit
        commitJobs[edit.key] = coroutineScope.launch {
            delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
            if (persist(edit) && pendingEdits[edit.key] == edit) cancel(edit)
        }
    }

    suspend fun flush() {
        val jobs = commitJobs.values.toList()
        commitJobs.clear()
        jobs.forEach { it.cancelAndJoin() }
        pendingEdits.values.toList().forEach { edit ->
            if (persist(edit)) pendingEdits.remove(edit.key)
        }
    }

    private suspend fun persist(edit: PendingSetupCommandEdit): Boolean {
        val persistedPath = prepareRepositoryPath(edit.key.repositoryIndex)
        if (persistedPath == null) {
            configPersistence.discard(edit.persistenceKey)
            return true
        }
        val committed = configPersistence.update(edit.persistenceKey) { currentConfig ->
            currentConfig.withSetupCommandReplaced(
                repositoryIndex = edit.key.repositoryIndex,
                expectedRepositoryPath = persistedPath,
                commandIndex = edit.key.commandIndex,
                replacementCommand = edit.replacementCommand,
            )
        }
        return committed
    }
}

private fun MutableStateFlow<EngHubSettingsUiState>.updateCommandDraft(
    repositoryIndex: Int,
    commandIndex: Int,
    command: String,
) {
    updateRepositoryUiState(repositoryIndex) { repository ->
        repository.copy(
            setupCommands = repository.setupCommands.withReplaced(commandIndex, command),
            setupCommandEditErrors = if (command.isBlank()) {
                repository.setupCommandEditErrors + (commandIndex to SETUP_COMMAND_BLANK_ERROR)
            } else {
                repository.setupCommandEditErrors - commandIndex
            },
        )
    }
}

private fun Map<Int, String>.withoutIndex(removedIndex: Int): Map<Int, String> = entries.mapNotNull { (index, error) ->
    when {
        index == removedIndex -> null
        index > removedIndex -> (index - 1) to error
        else -> index to error
    }
}.toMap()

private data class SetupCommandEditKey(
    val repositoryIndex: Int,
    val commandIndex: Int,
)

private data class PendingSetupCommandEdit(
    val key: SetupCommandEditKey,
    val persistenceKey: String,
    val replacementCommand: String,
) {
    fun withRepositoryIndex(repositoryIndex: Int) = copy(key = key.copy(repositoryIndex = repositoryIndex))
    fun withCommandIndex(commandIndex: Int) = copy(key = key.copy(commandIndex = commandIndex))
}
