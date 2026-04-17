plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets.named("commonMain") {
        dependencies {
            implementation(libs.findLibrary("kotlin-inject-runtime").get())
            implementation(libs.findLibrary("kotlin-inject-anvil-runtime").get())
            implementation(libs.findLibrary("kotlin-inject-anvil-runtime-optional").get())
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.findLibrary("kotlin-inject-compiler-ksp").get())
    add("kspCommonMainMetadata", libs.findLibrary("kotlin-inject-anvil-compiler").get())
    add("kspJvm", libs.findLibrary("kotlin-inject-compiler-ksp").get())
    add("kspJvm", libs.findLibrary("kotlin-inject-anvil-compiler").get())
}
