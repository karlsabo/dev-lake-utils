plugins {
    base
    alias(libs.plugins.spotless)
}

group = "com.github.karlsabo.devlake"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
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
