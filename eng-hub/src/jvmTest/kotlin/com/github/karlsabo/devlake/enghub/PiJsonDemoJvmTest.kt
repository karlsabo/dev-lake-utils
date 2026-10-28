package com.github.karlsabo.devlake.enghub

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class PiJsonDemoJvmTest {
    // Run explicitly with PI_JSON_DEMO_TEXT="..." ./gradlew :eng-hub:jvmTest --tests '*PiJsonDemoJvmTest'.
    @Test
    fun sendsTextToARealPiSession() = runBlocking {
        val text = System.getenv("PI_JSON_DEMO_TEXT") ?: return@runBlocking
        val result = PiJsonDemo.run(text) { prompt -> runPi(prompt) }
        println("Pi JSON result: $result")
        assertTrue(result.summary.isNotBlank())
    }

    private fun runPi(prompt: String): String {
        val process = ProcessBuilder(
            "pi", "--print", "--no-session", "--no-tools", "--no-extensions",
            "--no-mcp", "--no-skills", "--no-context-files", "--", prompt,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Pi timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) { "Pi failed (exit ${process.exitValue()}): $output" }
        return output
    }
}
