package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the record alignment [splitNulPaths] must preserve for NUL-delimited git output. */
class SplitNulPathsTest {
    @Test
    fun `keeps interior empty fields so following records stay aligned`() {
        val output = "empty.txt\u0000filter\u0000\u0000trap.txt\u0000filter\u0000evil\u0000"

        assertEquals(
            listOf("empty.txt", "filter", "", "trap.txt", "filter", "evil"),
            splitNulPaths(output),
        )
    }

    @Test
    fun `drops only the empty field a trailing separator leaves`() {
        assertEquals(listOf("first", "second"), splitNulPaths("first\u0000second\u0000"))
    }

    @Test
    fun `reports no fields for empty output`() {
        assertEquals(emptyList(), splitNulPaths(""))
    }
}
