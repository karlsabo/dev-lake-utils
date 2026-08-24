package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

private fun transactionPaths(primaryPath: Path): List<Path> = listOf(
    primaryPath,
    Path("$primaryPath.new"),
    Path("$primaryPath.bak"),
)

private fun validateDistinctTransactionPaths(
    configPath: Path,
    secretPath: Path,
    protectedPaths: Iterable<Path>,
    pathIdentity: (Path) -> String,
) {
    val configTransactionPaths = transactionPaths(configPath)
    val secretTransactionPaths = transactionPaths(secretPath)
    validateTransactionPathTypes(configTransactionPaths + secretTransactionPaths)
    val configIdentities = resolvePathIdentities(configTransactionPaths, pathIdentity)
    val secretIdentities = resolvePathIdentities(secretTransactionPaths, pathIdentity)
    val protectedIdentities = resolvePathIdentities(protectedPaths.toList(), pathIdentity)
    if (configIdentities.any(secretIdentities::contains)) {
        throw GitHubSecretWriteException(
            "GitHub secret path must not alias the GitHub configuration or its transaction files: $secretPath",
        )
    }
    val allIdentities = configIdentities + secretIdentities + protectedIdentities
    if (allIdentities.distinct().size != allIdentities.size) {
        throw GitHubSecretWriteException(
            "GitHub access transaction paths must be distinct from each other and protected paths",
        )
    }
}

private fun validateTransactionPathTypes(paths: List<Path>) = try {
    paths.forEach(::platformValidateRegularFileIfPresent)
} catch (error: IllegalArgumentException) {
    throwPathValidationException(error)
} catch (error: UnsupportedOperationException) {
    throwPathValidationException(error)
}

private fun validateConfigTransactionPathTypes(configPath: Path) {
    try {
        transactionPaths(configPath).forEach(::platformValidateRegularFileIfPresent)
    } catch (error: IllegalArgumentException) {
        throw GitHubConfigWriteException("Could not validate GitHub configuration transaction paths", error)
    } catch (error: UnsupportedOperationException) {
        throw GitHubConfigWriteException("Could not validate GitHub configuration transaction paths", error)
    }
}

private fun resolvePathIdentities(paths: List<Path>, pathIdentity: (Path) -> String): List<String> = try {
    paths.map(pathIdentity)
} catch (error: IOException) {
    throwPathValidationException(error)
} catch (error: IllegalArgumentException) {
    throwPathValidationException(error)
} catch (error: UnsupportedOperationException) {
    throwPathValidationException(error)
}

private fun throwPathValidationException(error: Throwable): Nothing = throw GitHubSecretWriteException(
    "Could not validate GitHub configuration and secret paths",
    error,
)

fun interface GitHubSecretWriter {
    suspend fun save(secretPath: Path, secret: GitHubSecret)
}

class GitHubConfigStore(
    private val secretFileWriter: GitHubSecretFileWriter = createGitHubSecretFileWriter(),
    private val moveFile: (Path, Path) -> Unit = SystemFileSystem::atomicMove,
    private val pathIdentity: (Path) -> String = ::platformFilePathIdentity,
) : GitHubSecretWriter {
    fun saveConfig(configPath: Path, config: GitHubConfig) {
        val pendingPath = Path("$configPath.new")
        val backupPath = Path("$configPath.bak")
        validateConfigTransactionPathTypes(configPath)
        try {
            translateFileSecurityException {
                deleteBestEffort(pendingPath)
                writeJson(pendingPath, lenientJson.encodeToString(GitHubConfig.serializer(), config))
                check(decodeGitHubConfigIfValid(pendingPath) == config) {
                    "Could not verify pending GitHub configuration"
                }
                promoteConfig(pendingPath, configPath, backupPath)
            }
        } catch (error: IOException) {
            throwWriteException(configPath, pendingPath, error)
        } catch (error: UnsupportedOperationException) {
            throwWriteException(configPath, pendingPath, error)
        } catch (error: IllegalStateException) {
            throwWriteException(configPath, pendingPath, error)
        } catch (error: IllegalArgumentException) {
            throwWriteException(configPath, pendingPath, error)
        } catch (error: FileSecurityException) {
            throwWriteException(configPath, pendingPath, error.securityCause)
        }
    }

    override suspend fun save(secretPath: Path, secret: GitHubSecret) {
        secretFileWriter.write(secretPath, lenientJson.encodeToString(GitHubSecret.serializer(), secret))
    }

    suspend fun saveAccess(
        configPath: Path,
        secretPath: Path,
        secret: GitHubSecret,
        protectedPaths: Iterable<Path> = emptyList(),
    ): LoadedGitHubConfig {
        validateSecretPath(configPath, secretPath, protectedPaths)
        save(secretPath, secret)
        val config = GitHubConfig(tokenPath = secretPath.toString())
        saveConfig(configPath, config)
        return LoadedGitHubConfig(config, secret)
    }

    fun validateSecretPath(
        configPath: Path,
        secretPath: Path,
        protectedPaths: Iterable<Path> = emptyList(),
    ) {
        validateDistinctTransactionPaths(configPath, secretPath, protectedPaths, pathIdentity)
    }

    private fun promoteConfig(
        pendingPath: Path,
        configPath: Path,
        backupPath: Path,
    ) {
        val rotatedPrimary = decodeGitHubConfigIfValid(configPath) != null
        if (rotatedPrimary) translateFileSecurityException { moveFile(configPath, backupPath) }
        try {
            translateFileSecurityException { moveFile(pendingPath, configPath) }
        } catch (error: IOException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
        } catch (error: UnsupportedOperationException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
        } catch (error: FileSecurityException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, configPath, error)
        }
    }

    private fun writeJson(path: Path, json: String) {
        SystemFileSystem.sink(path, false).buffered().use { sink ->
            sink.writeString(json)
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
            translateFileSecurityException { moveFile(backupPath, configPath) }
        } catch (_: IOException) {
            // The promotion failure is the useful error to report.
        } catch (_: UnsupportedOperationException) {
            // The promotion failure is the useful error to report.
        } catch (_: FileSecurityException) {
            // The promotion failure is the useful error to report.
        }
    }

    private fun throwWriteException(
        configPath: Path,
        pendingPath: Path,
        error: Throwable,
    ): Nothing {
        deleteBestEffort(pendingPath)
        throw GitHubConfigWriteException("Could not write GitHub configuration file: $configPath", error)
    }

    private fun deleteBestEffort(path: Path) {
        try {
            translateFileSecurityException { SystemFileSystem.delete(path, mustExist = false) }
        } catch (_: IOException) {
            // A later write reports the actionable storage failure.
        } catch (_: FileSecurityException) {
            // A later write reports the actionable storage failure.
        }
    }
}
