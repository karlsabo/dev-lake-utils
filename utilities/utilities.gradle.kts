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

// Helper function to create JavaExec tasks with lazy configuration
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
    taskName = "createUsersAndTeams",
    mainClassName = "com.github.karlsabo.devlake.CreateUsersAndTeamsKt",
    compilationName = "main"
)

createJvmExecTask(
    taskName = "gitHubApiDemo",
    mainClassName = "com.github.karlsabo.github.GitHubApiDemoKt",
    compilationName = "test"
)

createJvmExecTask(
    taskName = "notificationCleanupDemo",
    mainClassName = "com.github.karlsabo.github.notification.GitHubNotificationsCleanupDemoKt",
    compilationName = "test"
) {
    dependsOn("jvmTestClasses")
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
