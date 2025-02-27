plugins {
    kotlin("multiplatform") version libs.versions.kotlin apply false
    id("org.jetbrains.compose") version libs.versions.compose apply false
    kotlin("plugin.compose") version libs.versions.kotlin apply false
    kotlin("plugin.serialization") version libs.versions.kotlin apply false
    kotlin("jvm") version libs.versions.kotlin apply false
}
