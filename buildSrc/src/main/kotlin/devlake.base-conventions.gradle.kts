repositories {
    mavenCentral()
    google()
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}
