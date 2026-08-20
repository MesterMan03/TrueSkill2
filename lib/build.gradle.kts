import com.diffplug.spotless.LineEnding
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
    id("com.diffplug.spotless") version "7.0.2"
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

base {
    archivesName.set("trueskill2")
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

dokka {
    dokkaPublications.html {
        moduleName.set("TrueSkill 2")
        moduleVersion.set(project.version.toString())
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
    dokkaSourceSets.main {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl.set(uri("https://github.com/MesterMan03/TrueSkill2/tree/main/lib/src/main/kotlin"))
            remoteLineSuffix.set("#L")
        }
    }
}

mavenPublishing {
    coordinates("info.mester", "trueskill2", project.version.toString())
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("TrueSkill 2")
        description.set("A Kotlin implementation of Microsoft's TrueSkill 2 Bayesian skill rating model.")
        inceptionYear.set("2026")
        url.set("https://github.com/MesterMan03/TrueSkill2")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("MesterMan03")
                name.set("MesterMan03")
                email.set("MesterMan03+github@proton.me")
                url.set("https://github.com/MesterMan03")
                organization.set("MesterMan03")
                organizationUrl.set("https://mester.info")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/MesterMan03/TrueSkill2.git")
            developerConnection.set("scm:git:ssh://git@github.com/MesterMan03/TrueSkill2.git")
            url.set("https://github.com/MesterMan03/TrueSkill2")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/MesterMan03/TrueSkill2/issues")
        }
    }
}
