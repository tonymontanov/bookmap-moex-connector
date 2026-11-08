plugins {
    // Если JDK 21 на машине не найден, Gradle скачает его сам.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bookmap-moex-connector"
