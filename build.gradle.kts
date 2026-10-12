import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.17.0"
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
    // The IntelliJ Platform already provides `org.intellij.markdown` at runtime, so
    // ship against it as compileOnly. Bundling our own copy duplicates IDE packages
    // and trips the marketplace verifier's "Plugin bundles IDE packages" warning.
    compileOnly("org.jetbrains:markdown:0.7.3")
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

    // Ktor MockEngine for service-level tests; coroutines-test for runTest.
    // Both transitively pull a *vanilla* kotlinx-coroutines-core, which must be excluded
    // for the same reason as the main-source Ktor deps above: the IntelliJ Platform bundles
    // its own JetBrains-patched coroutines on the test classpath, and a duplicate vanilla
    // copy shadows it. That patched build adds internal methods (e.g.
    // runBlockingWithParallelismCompensation) the platform's indexing scanner calls — with
    // the vanilla copy on the classpath the scanner dies with NoSuchMethodError and
    // BasePlatformTestCase setUp hangs until "Indexing timeout". Tests use the platform's
    // bundled coroutines-core at runtime; coroutines-test itself is kept.
    testImplementation("io.ktor:ktor-client-mock:2.3.13") { excludeCoroutines() }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0") { excludeCoroutines() }
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
    // The Kotlin compiler is newer than the stdlib bundled by our oldest supported
    // IDE (sinceBuild = 243 / 2024.3, which ships Kotlin 2.0). We don't bundle our own
    // stdlib (kotlin.stdlib.default.dependency = false) and rely on the platform's, so
    // pin the API/language level to 2.0 to avoid emitting references to stdlib APIs that
    // only exist in newer Kotlin runtimes — otherwise the plugin would crash on 2024.3.
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        // Inherit platform interface default methods directly instead of emitting
        // compatibility bridge overrides. Without this, implementing ToolWindowFactory /
        // StatusBarWidget makes Kotlin generate delegating overrides of their default
        // methods (isApplicable, isDoNotActivateOnStart, getPresentation(PlatformType),
        // getAnchor, getIcon, manage) — code we never wrote — which the plugin verifier
        // then flags as deprecated/experimental/internal API usage. `all` drops those
        // bridges so only methods we actually override are reported.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

/** Helper for the dependency exclusions in the `dependencies { ... }` block above. */
fun org.gradle.api.artifacts.ModuleDependency.excludeCoroutines() {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
}

// Per-dependency excludes above aren't enough on the test classpath: kotlinx-coroutines-test
// resolves to its -jvm variant, whose dependency on kotlinx-coroutines-core-jvm gets pulled
// back in through Gradle's variant/constraint alignment. Exclude the vanilla core at the
// configuration level so the ONLY kotlinx.coroutines on the test classpath is the patched
// build bundled inside the IntelliJ Platform artifact (that one lives inside the IDE
// distribution, not as these module coordinates, so it is not affected). Without this the
// platform's indexing scanner hits NoSuchMethodError and BasePlatformTestCase setUp hangs
// until "Indexing timeout". coroutines-test itself is retained.
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach {
    it.configure {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended: omit until-build from plugin.xml (see gradle.properties).
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Fail only on problems that mean the plugin won't actually work. The plugin
        // verifier's default failure level (since IntelliJ Platform Gradle Plugin 2.x)
        // also fails on INTERNAL_API_USAGES — but our only such "usages" are the synthetic
        // bridge methods Kotlin emits for ToolWindowFactory's default methods (getAnchor,
        // getIcon, manage) when we implement the interface; they aren't code we wrote and
        // can't be removed without breaking older-IDE compatibility. Deprecated/experimental/
        // internal usages stay in the report as advisories, they just don't fail the build.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INVALID_PLUGIN,
        )
        ides {
            // Pinned to specific known-good versions. We previously used `recommended()`,
            // but JetBrains' index can advertise an IDE version (e.g. IC-2026.2.1) before
            // the actual artifact is published, breaking the verifier with "Could not
            // find idea:ideaIC:…". Pinning bottoms out our verification at the lowest
            // supported build (sinceBuild = 243) and steps up to the latest stable release.
            //
            // JetBrains stopped publishing the standalone IntelliJ IDEA Community (ideaIC)
            // distribution at 2025.3 (build 253); Community and Ultimate unified into a single
            // `idea` artifact. So use IntellijIdeaCommunity only up to 2025.2, and the unified
            // IntellijIdea type from 2025.3 on — using IntellijIdeaCommunity there resolves to
            // the nonexistent idea:ideaIC:<ver> and fails the verifier.
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.2.3")
            // IDEA alone doesn't prove the plugin works elsewhere: other products bundle
            // Git4Idea / the GitHub plugin differently. Check the newest release of the
            // non-IDEA products we care about (PyCharm is the primary target).
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.PyCharm, "2026.2.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.GoLand, "2026.2.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.WebStorm, "2026.2.3")
        }
    }
    // Marketplace publishing — driven by `./gradlew publishPlugin` in the release
    // workflow. Reads PUBLISH_TOKEN from the environment; the workflow forwards it
    // from the JETBRAINS_PUBLISH_TOKEN repository secret. Optional: set CHANNELS env
    // to publish to a non-default channel like `beta`.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.environmentVariable("CHANNELS")
            .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
            .orElse(listOf("default"))
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
