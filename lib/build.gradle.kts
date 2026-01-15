import com.diffplug.spotless.LineEnding

plugins {
    kotlin("jvm")
    `java-library`
    id("com.diffplug.spotless") version "7.0.2"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

spotless {
    kotlin {
        targetExclude("build/generated/**/*")
        targetExclude("build/generated-src/**/*")
        toggleOffOn()
        ktlint("1.7.1")
        lineEndings = LineEnding.GIT_ATTRIBUTES
        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
}

tasks.test {
    useJUnitPlatform()
}
