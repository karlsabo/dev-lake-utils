package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.tools.lenientJson
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal fun saveEngHubConfig(
    config: EngHubConfig,
    configPath: Path,
    verifyPending: (Path) -> EngHubConfig? = ::decodeEngHubConfigIfValid,
) {
    val pendingPath = Path("$configPath.new")
    val backupPath = Path("$configPath.bak")
    var pendingCreated = false
    try {
        validateEngHubConfigTransactionPaths(configPath, pendingPath, backupPath)
        replaceEngHubConfigPendingFile(
            pendingPath,
            lenientJson.encodeToString(EngHubConfig.serializer(), config),
        )
        pendingCreated = true
        check(verifyPending(pendingPath) == config) {
            "Could not verify pending Eng Hub configuration"
        }
        validateEngHubConfigTransactionPaths(configPath, pendingPath, backupPath)
        promoteEngHubConfig(pendingPath, configPath, backupPath)
    } catch (error: IOException) {
        throwWriteException(configPath, pendingPath, pendingCreated, error)
    } catch (error: UnsupportedOperationException) {
        throwWriteException(configPath, pendingPath, pendingCreated, error)
    } catch (error: IllegalStateException) {
        throwWriteException(configPath, pendingPath, pendingCreated, error)
    } catch (error: SecurityException) {
        throwWriteException(configPath, pendingPath, pendingCreated, error)
    } catch (error: IllegalArgumentException) {
        throwWriteException(configPath, pendingPath, pendingCreated, error)
    }
}

private fun throwWriteException(
    configPath: Path,
    pendingPath: Path,
    pendingCreated: Boolean,
    error: Throwable,
): Nothing {
    if (pendingCreated) deleteBestEffort(pendingPath)
    throw EngHubConfigWriteException("Could not save Eng Hub configuration at $configPath", error)
}

private fun promoteEngHubConfig(
    pendingPath: Path,
    configPath: Path,
    backupPath: Path,
) {
    val rotatedPrimary = decodeEngHubConfigIfValid(configPath) != null
    if (rotatedPrimary) SystemFileSystem.atomicMove(configPath, backupPath)
    try {
        SystemFileSystem.atomicMove(pendingPath, configPath)
    } catch (error: IOException) {
        restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
    } catch (error: UnsupportedOperationException) {
        restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
    } catch (error: IllegalStateException) {
        restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
    } catch (error: SecurityException) {
        restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
    } catch (error: IllegalArgumentException) {
        restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
    }
}

private fun restoreAfterPromotionFailure(
    rotatedPrimary: Boolean,
    backupPath: Path,
    configPath: Path,
    error: Throwable,
): Nothing {
    if (rotatedPrimary) restoreBackupBestEffort(backupPath, configPath)
    throw error
}

private fun restoreBackupBestEffort(backupPath: Path, configPath: Path) {
    try {
        SystemFileSystem.atomicMove(backupPath, configPath)
    } catch (_: IOException) {
        // The original promotion failure remains the actionable error.
    } catch (_: UnsupportedOperationException) {
        // The original promotion failure remains the actionable error.
    } catch (_: IllegalStateException) {
        // The original promotion failure remains the actionable error.
    } catch (_: SecurityException) {
        // The original promotion failure remains the actionable error.
    } catch (_: IllegalArgumentException) {
        // The original promotion failure remains the actionable error.
    }
}

private fun deleteBestEffort(path: Path) {
    try {
        SystemFileSystem.delete(path, mustExist = false)
    } catch (_: IOException) {
        // Cleanup must not hide the storage failure that prevented the save.
    } catch (_: IllegalStateException) {
        // Cleanup must not hide the storage failure that prevented the save.
    } catch (_: SecurityException) {
        // Cleanup must not hide the storage failure that prevented the save.
    } catch (_: IllegalArgumentException) {
        // Cleanup must not hide the storage failure that prevented the save.
    }
}

class EngHubConfigWriteException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
