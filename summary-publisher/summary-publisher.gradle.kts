import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("devlake.kotlin-multiplatform-compose-conventions")
    id("devlake.kotlin-inject-conventions")
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
                implementation(compose.desktop.currentOs)
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

data class JvmExecTaskSpec(
    val taskName: String,
    val mainClassName: String,
    val compilationName: String = "main",
    val taskGroup: String = "run",
    val supportsArgs: Boolean = false,
)

fun createJvmExecTask(
    spec: JvmExecTaskSpec,
    configure: JavaExec.() -> Unit = {},
) {
    tasks.register<JavaExec>(spec.taskName) {
        group = spec.taskGroup
        mainClass.set(spec.mainClassName)

        if (spec.supportsArgs) {
            val argLine: String? = project.findProperty("args") as String?
            if (argLine != null) {
                args = argLine.split("\\s+".toRegex())
            }
        }

        val jvmTarget = kotlin.targets.named("jvm")
        val compilation = jvmTarget.flatMap { target ->
            target.compilations.named(spec.compilationName)
        }

        classpath(
            compilation.map { it.output.allOutputs },
            compilation.map { it.runtimeDependencyFiles ?: files() },
        )

        configure()
    }
}

createJvmExecTask(
    JvmExecTaskSpec(
        taskName = "runSummaryDemo",
        mainClassName = "com.github.karlsabo.devlake.tools.SummaryDemoKt",
        compilationName = "test",
    ),
)

createJvmExecTask(
    JvmExecTaskSpec(
        taskName = "runSummaryDetailDemo",
        mainClassName = "com.github.karlsabo.devlake.tools.SummaryDetailDemoKt",
        compilationName = "test",
        supportsArgs = true,
    ),
)

createJvmExecTask(
    JvmExecTaskSpec(
        taskName = "runUiDemo",
        mainClassName = "com.github.karlsabo.devlake.tools.UiDemoKt",
        compilationName = "test",
    ),
)

createJvmExecTask(
    JvmExecTaskSpec(
        taskName = "runSummaryPublisherWithConfig",
        mainClassName = "com.github.karlsabo.devlake.tools.SummaryPublisherKt",
        compilationName = "main",
        supportsArgs = true,
    ),
)

createJvmExecTask(
    JvmExecTaskSpec(
        taskName = "runJiraTeamMerDemo",
        mainClassName = "com.github.karlsabo.devlake.tools.JiraTeamMerDemoKt",
        compilationName = "test",
        supportsArgs = true,
    ),
)
