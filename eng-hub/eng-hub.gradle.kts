import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val engHubDisplayName = "Eng Hub"
val engHubPackageName = "eng-hub"

plugins {
    id("devlake.kotlin-multiplatform-compose-conventions")
    id("devlake.kotlin-inject-conventions")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":utilities"))
                implementation(project(":shared-resources"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.desktop)
                implementation(libs.compose.components.resources)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.bundles.junit)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.bundles.log4j.runtime)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.skiko.macos.arm64)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.github.karlsabo.devlake.enghub.MainKt"
        jvmArgs += listOf("-Xdock:name=$engHubDisplayName")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = engHubPackageName
            packageVersion = "1.0.0"
        }
    }
}

fun createJvmExecTask(
    taskName: String,
    mainClassName: String,
    compilationName: String = "main",
    taskGroup: String = "application",
    configure: JavaExec.() -> Unit = {},
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

        configure()
    }
}

createJvmExecTask(
    taskName = "syncLlmFiles",
    mainClassName = "com.github.karlsabo.devlake.enghub.LlmSkillSyncMainKt",
) {
    args(rootProject.projectDir.resolve("llm").absolutePath)
}
