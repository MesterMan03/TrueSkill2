plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

val libraryVersion = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT")

allprojects {
    group = "info.mester"
    version = libraryVersion.get()

    repositories {
        mavenCentral()
    }
}
