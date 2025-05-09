import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        withJava()
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Test
                implementation(kotlin("test"))

                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinxCoroutines.get()}")
            }
        }
        getByName("jvmMain") {
            dependencies {
                implementation(project(":utilities"))

                implementation(compose.runtime)
                implementation(compose.desktop.currentOs)

                val markdownVersion = libs.versions.markdown.get()
                implementation("com.mikepenz:multiplatform-markdown-renderer:$markdownVersion")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m2:$markdownVersion")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownVersion")

                // ktor
                val ktorVersion = libs.versions.ktor.get()
                implementation("io.ktor:ktor-client-core:${ktorVersion}")
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-serialization-gson:$ktorVersion")

                // IO
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:${libs.versions.kotlinxIo.get()}")
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

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}
