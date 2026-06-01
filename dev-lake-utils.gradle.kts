import io.gitlab.arturbosch.detekt.Detekt

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
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
    baseline = file("config/detekt/baseline.xml")
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

spotless {
    isEnforceCheck = true

    kotlin {
        target("buildSrc/src/**/*.kt", "*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
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
