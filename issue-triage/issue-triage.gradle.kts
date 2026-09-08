plugins {
    id("devlake.kotlin-multiplatform-conventions")
}

group = parent!!.group
version = parent!!.version

kotlin {
    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(project(":utilities"))
                implementation(libs.odfdom.java)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                runtimeOnly(libs.bundles.log4j.runtime)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Exports a Linear issue inventory to an ODS workbook."
    mainClass.set("com.github.karlsabo.devlake.triage.IssueTriageCliKt")

    val compilation = kotlin.targets.named("jvm").flatMap { target ->
        target.compilations.named("main")
    }
    classpath(
        compilation.map { it.output.allOutputs },
        compilation.map { it.runtimeDependencyFiles ?: files() },
    )
}
