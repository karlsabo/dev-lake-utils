import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("devlake.kotlin-multiplatform-compose-conventions")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":utilities"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.desktop)
                implementation(libs.compose.components.resources)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.bundles.log4j.runtime)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.skiko.macos.arm64)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.github.karlsabo.devlake.ghpanel.GitHubControlPanelKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "github-control-panel"
            packageVersion = "1.0.0"
        }
    }
}
