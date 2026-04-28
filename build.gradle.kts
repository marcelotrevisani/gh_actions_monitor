import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get()
        )
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins").map {
                it.split(",").map(String::trim)
            }
        )
        testFramework(TestFrameworkType.Platform)
    }

    // JUnit 5 for pure unit tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")

    // Required by IntelliJ Platform tests on 2024.3+
    testImplementation("junit:junit:4.13.2")

    // Kotlin test assertions (used by both unit and platform tests)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // JUnit 5 for tests outside BasePlatformTestCase; platform tests run via JUnit 4.
    test {
        useJUnitPlatform {
            // BasePlatformTestCase subclasses are JUnit 3-style; the platform engine picks them up.
            includeEngines("junit-jupiter", "junit-vintage")
        }
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
