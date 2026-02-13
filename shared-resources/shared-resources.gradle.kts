plugins {
    id("devlake.kotlin-multiplatform-compose-conventions")
}

compose.resources {
    publicResClass = true
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.components.resources)
            }
        }
    }
}
