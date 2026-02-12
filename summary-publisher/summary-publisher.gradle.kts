import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("devlake.kotlin-multiplatform-compose-conventions")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Test
                implementation(kotlin("test"))

                // Coroutines
                implementation(libs.kotlinx.coroutines.core)

                implementation(project(":utilities"))

                implementation(libs.compose.runtime)
                implementation(libs.compose.desktop)

                implementation(libs.bundles.markdown.renderer)

                // ktor
                implementation(libs.bundles.ktor.client)
                implementation(libs.ktor.serialization.gson)

                // IO
                implementation(libs.kotlinx.io.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.bundles.log4j.runtime)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.github.karlsabo.devlake.tools.SummaryPublisherKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "demo"
            packageVersion = "1.0.0"
        }
    }
}

// Helper function to create JavaExec tasks with lazy configuration
fun createJvmExecTask(
    taskName: String,
    mainClassName: String,
    compilationName: String = "main",
    taskGroup: String = "run",
    supportsArgs: Boolean = false,
    configure: JavaExec.() -> Unit = {},
) {
    tasks.register<JavaExec>(taskName) {
        group = taskGroup
        mainClass.set(mainClassName)

        if (supportsArgs) {
            val argLine: String? = project.findProperty("args") as String?
            if (argLine != null) {
                args = argLine.split("\\s+".toRegex())
            }
        }

        val jvmTarget = kotlin.targets.named("jvm")
        val compilation = jvmTarget.flatMap { target ->
            target.compilations.named(compilationName)
        }

        classpath(
            compilation.map { it.output.allOutputs },
            compilation.map { it.runtimeDependencyFiles ?: files() }
        )

        configure()
    }
}

createJvmExecTask(
    taskName = "runSummaryDemo",
    mainClassName = "com.github.karlsabo.devlake.tools.SummaryDemoKt",
    compilationName = "test"
)

createJvmExecTask(
    taskName = "runSummaryDetailDemo",
    mainClassName = "com.github.karlsabo.devlake.tools.SummaryDetailDemoKt",
    compilationName = "test",
    supportsArgs = true
)

createJvmExecTask(
    taskName = "runUiDemo",
    mainClassName = "com.github.karlsabo.devlake.tools.UiDemoKt",
    compilationName = "test"
)

createJvmExecTask(
    taskName = "runSummaryPublisherWithConfig",
    mainClassName = "com.github.karlsabo.devlake.tools.SummaryPublisherKt",
    compilationName = "main",
    supportsArgs = true
)

createJvmExecTask(
    taskName = "runJiraTeamMerDemo",
    mainClassName = "com.github.karlsabo.devlake.tools.JiraTeamMerDemoKt",
    compilationName = "test",
    supportsArgs = true
)
