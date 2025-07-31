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
    jvm {
        withJava()
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
            }
        }

        create("posixMain") {
            dependsOn(getByName("commonMain"))
        }

        getByName("macosArm64Main") {
            dependsOn(getByName("posixMain"))
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
