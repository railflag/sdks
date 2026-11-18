plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "com.railflag"

repositories {
    mavenCentral()
}

dependencies {
    // The only runtime dependency. It brings slf4j-api, which the SDK logs through.
    api("dev.openfeature:sdk:1.23.0")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// Java 11 bytecode and API, whatever JDK runs Gradle.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    // -classfile: OpenFeature's classes carry SpotBugs annotations that aren't on the classpath.
    options.compilerArgs.addAll(listOf("-Xlint:all,-classfile", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // `-PtestJava=11` runs the tests on another installed JDK (CI checks 11 and 21).
    providers.gradleProperty("testJava").orNull?.let { v ->
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(v)) })
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Published to Maven Central by .github/workflows/release-sdks.yml (tag sdk-java-v<version>).
mavenPublishing {
    publishToMavenCentral()
    // The release job passes the key as ORG_GRADLE_PROJECT_signingInMemoryKey; local builds skip signing.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.railflag", "railflag-openfeature", version.toString())
    pom {
        name.set("Railflag OpenFeature provider")
        description.set("Railflag's OpenFeature provider for Java and Kotlin: evaluates feature flags in process and streams changes.")
        url.set("https://railflag.com")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("railflag")
                name.set("Railflag")
                url.set("https://railflag.com")
            }
        }
        scm {
            url.set("https://github.com/railflag/sdks/tree/main/packages/sdk-java")
            connection.set("scm:git:https://github.com/railflag/sdks.git")
        }
    }
}

// src/examples: LiveCheck, an end-to-end check against a running Railflag (`./gradlew liveCheck`).
val examples: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[examples.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
    "examplesRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}
tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Checks the SDK against a running Railflag: set SY_EMAIL and SY_PASSWORD (and SY_API, SY_RELAY)."
    classpath = examples.runtimeClasspath
    mainClass.set("com.railflag.openfeature.LiveCheck")
}
