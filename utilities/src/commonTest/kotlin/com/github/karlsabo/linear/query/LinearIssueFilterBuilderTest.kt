package com.github.karlsabo.linear.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinearIssueFilterBuilderTest {
    private val builder = LinearIssueFilterBuilder()

    @Test
    fun `builds a configured team and project filter`() {
        assertEquals(
            "{ team: { key: { eq: \"PLAT\" } }, project: { name: { eq: \"Platform Reliability\" } } }",
            builder.triageScopeFilter(teamKey = "PLAT", projectName = "Platform Reliability"),
        )
    }

    @Test
    fun `builds a configured team and label filter`() {
        assertEquals(
            "{ team: { key: { eq: \"PLAT\" } }, labels: { name: { eq: \"reliability-review\" } } }",
            builder.triageScopeFilter(teamKey = "PLAT", labelName = "reliability-review"),
        )
    }

    @Test
    fun `requires exactly one selector per query`() {
        assertFailsWith<IllegalArgumentException> {
            builder.triageScopeFilter(teamKey = "TST")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.triageScopeFilter(teamKey = "TST", projectName = "Test Project", labelName = "test-label")
        }
    }
}
