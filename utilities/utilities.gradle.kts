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
                implementation("io.ktor:ktor-serialization-gson:$ktorVersion")
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

        getByName("jvmMain") {
            dependencies {
                implementation(libs.guava)

                // DB
                api("com.zaxxer:HikariCP:5.1.0")
                implementation("mysql:mysql-connector-java:8.0.33")


                // logging
                implementation("org.slf4j:slf4j-api:2.0.16")
                val log4jVersion = "2.24.1"
                implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
                implementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
                implementation("org.apache.logging.log4j:log4j-api:$log4jVersion")
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

publishing {
    repositories {
        mavenLocal()
    }
}
