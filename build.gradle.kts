import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
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

    // HTTP and JSON. Each Ktor dep excludes its transitive kotlinx-coroutines-core because the
    // IntelliJ Platform already bundles coroutines on the runtime classpath; pulling our own
    // copy alongside causes a loader-constraint violation when StateFlow / Flow are loaded
    // twice (once by PluginClassLoader, once by PathClassLoader).
    implementation("io.ktor:ktor-client-core:2.3.13") { excludeCoroutines() }
    implementation("io.ktor:ktor-client-cio:2.3.13") { excludeCoroutines() }
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13") { excludeCoroutines() }
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13") { excludeCoroutines() }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains:markdown:0.7.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JUnit 5 for pure unit tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")

    // Required by IntelliJ Platform tests on 2024.3+
    testImplementation("junit:junit:4.13.2")
    // Discovers JUnit 3/4 tests (BasePlatformTestCase) under JUnit Platform.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")

    // Kotlin test assertions (used by both unit and platform tests)
    testImplementation(kotlin("test"))

    // Ktor MockEngine for service-level tests; coroutines-test for runTest
    testImplementation("io.ktor:ktor-client-mock:2.3.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

/** Helper for the dependency exclusions in the `dependencies { ... }` block above. */
fun org.gradle.api.artifacts.ModuleDependency.excludeCoroutines() {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
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
        // The 'com.example' id prefix is a placeholder; the marketplace-acceptable id is set in Plan 9.
        // Until then, suppress the verifier's prefix check rather than blocking the build.
        freeArgs = listOf("-mute", "ForbiddenPluginIdPrefix")
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
