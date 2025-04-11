plugins {
    kotlin("multiplatform") version libs.versions.kotlin apply false
    id("org.jetbrains.compose") version libs.versions.compose apply false
    kotlin("plugin.compose") version libs.versions.kotlin apply false
    kotlin("plugin.serialization") version libs.versions.kotlin apply false
    kotlin("jvm") version libs.versions.kotlin apply false
}

group = "com.github.karlsabo.devlake"
version = "0.1.0-SNAPSHOT"
