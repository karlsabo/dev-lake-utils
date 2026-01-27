plugins {
    id("devlake.kotlin-multiplatform-conventions")
    `maven-publish`
}

group = parent!!.group
version = parent!!.version

kotlin {
    macosArm64 {
        binaries {
            framework {
                baseName = "utilities"
            }
            sharedLib {
                baseName = "utilities"
            }
            staticLib {
                baseName = "utilities"
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Logging
                api(libs.kotlinLogging)

                // IO
                api(libs.kotlinx.io.core)

                // kotlin
                api(libs.kotlinx.datetime)

                // ktor
                implementation(libs.bundles.ktor.client.full)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.slf4j.api)
                implementation(libs.bundles.log4j.runtime)
                implementation(libs.bundles.junit)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }

    // Apply native-specific opt-ins to all native targets
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.addAll(
                        "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                        "-opt-in=kotlin.experimental.ExperimentalNativeApi"
                    )
                }
            }
        }
    }
}

tasks.register<JavaExec>("createUsersAndTeams") {
    group = "application"
    mainClass.set("com.github.karlsabo.devlake.CreateUsersAndTeamsKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("main").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("gitHubApiDemo") {
    group = "application"
    mainClass.set("com.github.karlsabo.github.GitHubApiDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

tasks.register<JavaExec>("notificationCleanupDemo") {
    dependsOn("jvmTestClasses")

    group = "application"
    mainClass.set("com.github.karlsabo.github.notification.GitHubNotificationsCleanupDemoKt")

    val jvmCompilations = kotlin.targets.named("jvm").get().compilations.named("test").get()
    classpath = jvmCompilations.output.allOutputs + (jvmCompilations.runtimeDependencyFiles ?: files())
}

publishing {
    repositories {
        mavenLocal()
    }

    publications {
        withType<MavenPublication> {
            pom {
                name.set("Utilities")
                description.set("Utility libraries metrics")
            }
        }
    }
}
