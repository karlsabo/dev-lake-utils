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
        mainClass = "com.github.karlsabo.devlake.metrics.UserMetricPublisherKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "demo"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register<JavaExec>("runMetricsDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.metrics.MetricsDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runUserEpicsDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.metrics.UserEpicsDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runUserIssuesByParentDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.metrics.UserIssuesByParentDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runUserEpicsWithIssuesDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.metrics.UserEpicsWithIssuesDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("runLinearDemo") {
    group = "run"
    mainClass.set("com.github.karlsabo.devlake.metrics.LinearDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}
