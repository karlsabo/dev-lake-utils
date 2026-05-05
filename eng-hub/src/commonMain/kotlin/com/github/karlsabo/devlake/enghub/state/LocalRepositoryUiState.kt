package com.github.karlsabo.devlake.enghub.state

data class LocalRepositoryUiState(
    val name: String,
    val path: String,
)

fun List<String>.toLocalRepositoryUiStates(): List<LocalRepositoryUiState> {
    return asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { path ->
            LocalRepositoryUiState(
                name = path.repositoryFolderName(),
                path = path,
            )
        }
        .sortedWith(
            compareBy<LocalRepositoryUiState> { it.name.lowercase() }
                .thenBy { it.path.lowercase() }
        )
        .toList()
}

private fun String.repositoryFolderName(): String {
    val normalized = trimEnd('/', '\\')
    return normalized.substringAfterLast('/').substringAfterLast('\\').ifEmpty { normalized }
}
