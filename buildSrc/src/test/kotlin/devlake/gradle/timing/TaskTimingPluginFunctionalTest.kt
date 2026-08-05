package devlake.gradle.timing

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TaskTimingPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `reports an executed task in machine and human readable formats`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"timing-test\"")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("devlake.task-timing") }
            tasks.register("knownTask") { doLast { println("known task ran") } }
            """.trimIndent(),
        )

        val runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("knownTask", "-PtaskTiming=true", "--configuration-cache")
            .withPluginClasspath()

        runner.build()
        val reusedBuild = runner.build()

        assertContains(reusedBuild.output, "Reusing configuration cache.")
        val json = report("task-timing.json")
        assertContains(json, "\"path\":\":knownTask\"")
        assertContains(json, "\"outcome\":\"SUCCESS\"")
        val duration = Regex("\"durationMillis\":(\\d+)").find(json)?.groupValues?.get(1)?.toLong()
        assertTrue(duration != null && duration >= 0)

        val text = report("task-timing.txt")
        assertContains(text, ":knownTask")
        assertContains(text, "SUCCESS")
    }

    @Test
    fun `preserves distinct skipped task outcomes`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"timing-test\"")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("devlake.task-timing") }
            tasks.register<Copy>("noSource") {
                from("missing")
                into(layout.buildDirectory.dir("copied"))
            }
            tasks.register("explicitlySkipped") { onlyIf { false } }
            """.trimIndent(),
        )

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("noSource", "explicitlySkipped", "-PtaskTiming=true")
            .withPluginClasspath()
            .build()

        val json = report("task-timing.json")
        assertContains(json, "\"path\":\":noSource\"")
        assertContains(json, "\"outcome\":\"NO-SOURCE\"")
        assertContains(json, "\"path\":\":explicitlySkipped\"")
        assertContains(json, "\"outcome\":\"SKIPPED\"")
    }

    private fun report(name: String): String = projectDir.resolve("build/reports/$name").readText()
}
