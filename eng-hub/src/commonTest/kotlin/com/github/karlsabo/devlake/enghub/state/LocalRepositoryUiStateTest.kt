package com.github.karlsabo.devlake.enghub.state

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRepositoryUiStateTest {

    @Test
    fun sortsRepositoriesByFolderName() {
        val repositories = listOf(
            "/Users/karl.sabo/git/k-repo",
            "/Users/karl.sabo/git/app",
            "/Users/karl.sabo/git/infrastructure",
            "/Users/karl.sabo/git/fender",
        )

        val uiStates = repositories.toLocalRepositoryUiStates()

        assertEquals(
            listOf("app", "fender", "infrastructure", "k-repo"),
            uiStates.map { it.name },
        )
        assertEquals(
            listOf(
                "/Users/karl.sabo/git/app",
                "/Users/karl.sabo/git/fender",
                "/Users/karl.sabo/git/infrastructure",
                "/Users/karl.sabo/git/k-repo",
            ),
            uiStates.map { it.path },
        )
    }

    @Test
    fun derivesFolderNameFromTrailingSlashPath() {
        val uiStates = listOf("/Users/karl.sabo/git/app/").toLocalRepositoryUiStates()

        assertEquals(listOf(LocalRepositoryUiState(name = "app", path = "/Users/karl.sabo/git/app/")), uiStates)
    }
}
