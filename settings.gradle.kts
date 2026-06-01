pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "dev-lake-utils"
rootProject.buildFileName = "dev-lake-utils.gradle.kts"

include("summary-publisher")
project(":summary-publisher").buildFileName = "summary-publisher.gradle.kts"
include("utilities")
project(":utilities").buildFileName = "utilities.gradle.kts"
include("user-metrics-publisher")
project(":user-metrics-publisher").buildFileName = "user-metrics-publisher.gradle.kts"
include("eng-hub")
project(":eng-hub").buildFileName = "eng-hub.gradle.kts"
include("shared-resources")
project(":shared-resources").buildFileName = "shared-resources.gradle.kts"
