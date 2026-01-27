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

                implementation(compose.runtime)
                implementation(compose.desktop.currentOs)

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

tasks.register<JavaExec>("runSummaryDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.tools.SummaryDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runSummaryDetailDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.tools.SummaryDetailDemoKt")

    val argLine: String? = project.findProperty("args") as String?
    if (argLine != null) {
        args = argLine.split("\\s+".toRegex())
    }

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runUiDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.tools.UiDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runSummaryPublisherWithConfig") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.tools.SummaryPublisherKt")

    val argLine: String? = project.findProperty("args") as String?
    if (argLine != null) {
        args = argLine.split("\\s+".toRegex())
    }

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("main").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runJiraTeamMerDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.tools.JiraTeamMerDemoKt")

    val argLine: String? = project.findProperty("args") as String?
    if (argLine != null) {
        args = argLine.split("\\s+".toRegex())
    }

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}
