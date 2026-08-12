repositories {
    mavenCentral()
    google()
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false

    if (System.getProperty("os.name").startsWith("Mac")) {
        systemProperty("apple.awt.UIElement", "true")
    }
}
