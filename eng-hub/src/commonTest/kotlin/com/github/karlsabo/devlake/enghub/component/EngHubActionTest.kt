package com.github.karlsabo.devlake.enghub.component

import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubActionTest {

    @Test
    fun misspelledTitleWithinTwoEditsMatchesAction() {
        val settings = action("Settings")

        assertEquals(listOf(settings), filterEngHubActions(listOf(settings), "setings"))
    }

    @Test
    fun substringMatchesRankBeforeFuzzyMatches() {
        val fuzzyMatch = action("Settling")
        val substringMatch = action("Settings")

        assertEquals(
            listOf(substringMatch, fuzzyMatch),
            filterEngHubActions(listOf(fuzzyMatch, substringMatch), "setting"),
        )
    }

    @Test
    fun wordsFartherThanTwoEditsDoNotMatch() {
        assertEquals(emptyList(), filterEngHubActions(listOf(action("Settings")), "notifications"))
    }

    private fun action(title: String) = EngHubAction(title = title, onInvoke = {})
}
