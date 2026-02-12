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
        mainClass = "com.github.karlsabo.devlake.metrics.UserMetricPublisherKt"

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
    compilationName: String = "test",
    taskGroup: String = "run",
) {
    tasks.register<JavaExec>(taskName) {
        group = taskGroup
        mainClass.set(mainClassName)

        val jvmTarget = kotlin.targets.named("jvm")
        val compilation = jvmTarget.flatMap { target ->
            target.compilations.named(compilationName)
        }

        classpath(
            compilation.map { it.output.allOutputs },
            compilation.map { it.runtimeDependencyFiles ?: files() }
        )
    }
}

createJvmExecTask(
    taskName = "runMetricsDemo",
    mainClassName = "com.github.karlsabo.devlake.metrics.MetricsDemoKt"
)

createJvmExecTask(
    taskName = "runUserEpicsDemo",
    mainClassName = "com.github.karlsabo.devlake.metrics.UserEpicsDemoKt"
)

createJvmExecTask(
    taskName = "runUserIssuesByParentDemo",
    mainClassName = "com.github.karlsabo.devlake.metrics.UserIssuesByParentDemoKt"
)

createJvmExecTask(
    taskName = "runUserEpicsWithIssuesDemo",
    mainClassName = "com.github.karlsabo.devlake.metrics.UserEpicsWithIssuesDemoKt"
)

createJvmExecTask(
    taskName = "runLinearDemo",
    mainClassName = "com.github.karlsabo.devlake.metrics.LinearDemoKt"
)
