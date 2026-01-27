plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

group = parent!!.group
version = parent!!.version

repositories {
    mavenCentral()
    google()
}

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
                }
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
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
                api("org.jetbrains.kotlinx:kotlinx-io-core:${libs.versions.kotlinxIo.get()}")

                // kotlin
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

                // ktor
                val ktorVersion = libs.versions.ktor.get()
                implementation("io.ktor:ktor-client-core:${ktorVersion}")
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-client-logging:$ktorVersion")
                implementation("io.ktor:ktor-client-auth:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                val ktorVersion = libs.versions.ktor.get()
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinxCoroutines.get()}")
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.9")
                implementation("org.apache.logging.log4j:log4j-core:${libs.versions.log4jVersion.get()}")
                implementation("org.apache.logging.log4j:log4j-slf4j2-impl:${libs.versions.log4jVersion.get()}")
                implementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
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


tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
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
