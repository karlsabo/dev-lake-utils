import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.changelog.tasks.GetChangelogTask

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    alias(libs.plugins.jetbrains.changelog)
}

group = "com.github.karlsabo.devlake"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val detektSourceRoots = files(
    "dev-lake-utils.gradle.kts",
    "settings.gradle.kts",
    "buildSrc/build.gradle.kts",
    "buildSrc/settings.gradle.kts",
    "buildSrc/src/main/kotlin",
    subprojects.map { it.buildFile },
    subprojects.map { it.projectDir.resolve("src") },
)

detekt {
    source.setFrom(detektSourceRoots)
    buildUponDefaultConfig = true
    config.setFrom(layout.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.gradle/**")

    reports {
        html.required.set(true)
        txt.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.named("check") {
    dependsOn(tasks.named("detekt"))
}

tasks.named<GetChangelogTask>("getChangelog") {
    unreleased = true
}

abstract class PrintVersionTask : DefaultTask() {
    @get:Input
    abstract val versionText: Property<String>

    @TaskAction
    fun printVersion() {
        println(versionText.get())
    }
}

tasks.register<PrintVersionTask>("printVersion") {
    group = "help"
    description = "Prints the root project version."
    versionText.set(version.toString())
}

spotless {
    isEnforceCheck = true

    kotlin {
        target("buildSrc/src/**/*.kt", "*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:${libs.versions.composeRulesKtlint.get()}"))
    }

    kotlinGradle {
        target("*.gradle.kts", "settings.gradle.kts", "buildSrc/src/**/*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

allprojects {
    tasks.configureEach {
        if (!name.startsWith("clean")) {
            mustRunAfter(tasks.matching { it.name.startsWith("clean") })
        }
    }
}

tasks.wrapper {
    gradleVersion = "9.4.1"
    distributionType = Wrapper.DistributionType.ALL
}
