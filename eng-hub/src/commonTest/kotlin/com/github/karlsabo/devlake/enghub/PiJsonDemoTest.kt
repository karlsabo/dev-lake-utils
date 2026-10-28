package com.github.karlsabo.devlake.enghub

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PiJsonDemoTest {
    @Test
    fun sendsTextAndSchemaToPiAndDecodesItsResponse() = runBlocking {
        val result = PiJsonDemo.run("A laptop wakes and polling resumes") { prompt ->
            assertContains(prompt, PiJsonDemo.outputSchema)
            assertContains(prompt, "A laptop wakes and polling resumes")
            """{"summary":"Polling resumes after waking","tags":["laptop","polling"]}"""
        }

        assertEquals(PiJsonDemoResult("Polling resumes after waking", listOf("laptop", "polling")), result)
    }

    @Test
    fun rejectsResponsesThatDoNotMatchTheSchema() {
        runBlocking {
            assertFailsWith<SerializationException> {
                PiJsonDemo.run("Some text") { """{"summary":"Missing tags"}""" }
            }
            assertFailsWith<SerializationException> {
                PiJsonDemo.run("Some text") { """{"summary":"Extra field","tags":[],"other":true}""" }
            }
        }
    }
}
