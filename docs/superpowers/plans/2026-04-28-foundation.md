# Plan 1 — Foundation (Scaffold, Auth, Repo Binding, Empty Tool Window)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a working IntelliJ Platform plugin that authenticates the user against github.com or GitHub Enterprise Server, resolves the open project's GitHub repo, and shows a tool window with an empty state reflecting connection status — without yet making any GitHub API calls beyond a single test-connection request.

**Architecture:** Single-module Gradle project using the `org.jetbrains.intellij.platform` Gradle plugin. Kotlin source under `com.example.ghactions`. Auth resolution proceeds in priority order: matching IDE-configured GitHub account (via the bundled `org.jetbrains.plugins.github` plugin) → plugin-stored PAT in IntelliJ's `PasswordSafe`. The `RepoBinding` service derives the active repo from the project's git `origin` remote. UI in this plan is intentionally minimal — one tool window panel that switches between four empty-state messages.

**Tech Stack:** Kotlin 2.0.x, Gradle 8.x (Kotlin DSL), JDK 17, IntelliJ Platform Gradle plugin 2.x, JUnit 5 (pure unit tests), `BasePlatformTestCase` (platform tests). Targets PyCharm Community/Professional 2024.3+ and IntelliJ IDEA Community/Ultimate 2024.3+ for the dev IDE.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered by this plan: *Architecture → Module & package layout (partial)*, *Components → `auth/`, `repo/`, `events/`*, *Authentication & settings*, *UI → empty/error states*. Sections deferred to later plans: `api/` HTTP client, `polling/`, `RunRepository`, all run-data UI, annotations, summaries, artifacts, write actions, notifications, status bar widget.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
├── .gitignore                                          (new)
├── build.gradle.kts                                    (new)
├── settings.gradle.kts                                 (new)
├── gradle.properties                                   (new)
├── gradle/wrapper/gradle-wrapper.{jar,properties}      (new — generated)
├── gradlew, gradlew.bat                                (new — generated)
└── src/
    ├── main/
    │   ├── kotlin/com/example/ghactions/
    │   │   ├── auth/
    │   │   │   ├── AuthSource.kt                       (new) — sealed class for credential variants
    │   │   │   ├── GitHubAccountResolver.kt            (new) — picks credential source per host
    │   │   │   ├── PatStorage.kt                       (new) — PasswordSafe wrapper
    │   │   │   ├── PluginSettings.kt                   (new) — PersistentStateComponent (non-secret)
    │   │   │   └── TestConnection.kt                   (new) — single GET /user probe
    │   │   ├── events/
    │   │   │   └── Topics.kt                           (new) — MessageBus Topic constants
    │   │   ├── repo/
    │   │   │   └── RepoBinding.kt                      (new) — derives GitHub repo from git origin
    │   │   ├── settings/
    │   │   │   ├── GhActionsConfigurable.kt            (new) — Settings entry-point
    │   │   │   └── GhActionsSettingsPanel.kt           (new) — Swing panel
    │   │   └── ui/
    │   │       ├── GhActionsToolWindowFactory.kt       (new) — registers tool window
    │   │       └── EmptyStatePanel.kt                  (new) — shows connection/repo status
    │   └── resources/
    │       └── META-INF/
    │           └── plugin.xml                          (new)
    └── test/
        └── kotlin/com/example/ghactions/
            ├── auth/
            │   ├── AuthSourceTest.kt                   (new)
            │   ├── GitHubAccountResolverTest.kt        (new)
            │   ├── PatStorageTest.kt                   (new — platform test)
            │   └── PluginSettingsTest.kt               (new — platform test)
            ├── repo/
            │   └── RepoBindingTest.kt                  (new — platform test)
            └── ui/
                └── ToolWindowFactoryTest.kt            (new — platform test)
```

**File responsibility notes:**

- `AuthSource.kt` is a small sealed class shared by `auth/` and (in later plans) `api/`. Lives in `auth/` because that's where it originates.
- `PluginSettings.kt` and `PatStorage.kt` are deliberately separate: settings are non-secret and serialized by IntelliJ; tokens live exclusively in `PasswordSafe` and never touch `state.xml`.
- `GhActionsSettingsPanel.kt` is the Swing form; `GhActionsConfigurable.kt` is the `Configurable` adapter that the platform calls into. Splitting them keeps the form code testable in isolation.
- `EmptyStatePanel.kt` is the only UI panel built in this plan. Later plans add `RunListPanel`, `RunDetailPanel`, etc. as siblings under `ui/`.

---

## Conventions for this plan

- **Test framework:** Pure Kotlin unit tests use **JUnit 5** (`org.junit.jupiter.api.*`). Platform-level tests extend `BasePlatformTestCase` (still JUnit 3-style — that's how the IntelliJ test framework works; do *not* try to add JUnit 5 annotations to those). Both are configured side-by-side in `build.gradle.kts`.
- **Logger:** All non-test classes use `Logger.getInstance(<class>::class.java)` from `com.intellij.openapi.diagnostic.Logger`. Sub-namespacing isn't needed in this plan.
- **Coroutines:** Plan 1 doesn't introduce any coroutine-based code yet (no HTTP, no polling). The settings test-connection action uses a single `Thread` started by the action handler; it's swapped for a coroutine in Plan 2 when the HTTP client lands. **Reason:** keeping coroutines out of Plan 1 means we don't have to commit to a coroutine-scope shape until the polling coordinator dictates it.
- **Commits:** One commit per task. Commit message style: `<type>: <imperative summary>` matching the existing spec commit (e.g. `feat: add AuthSource sealed class`, `chore: scaffold gradle build`). No `Co-Authored-By` trailer.

---

## Task 1: Gradle scaffold

**Files:**
- Create: `gh_actions_pycharm/.gitignore`
- Create: `gh_actions_pycharm/settings.gradle.kts`
- Create: `gh_actions_pycharm/gradle.properties`
- Create: `gh_actions_pycharm/build.gradle.kts`

- [ ] **Step 1: Create `.gitignore`**

```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

# IDE
.idea/
*.iml
*.iws
*.ipr
out/

# OS
.DS_Store
Thumbs.db

# Local secrets — never commit
local.properties
*.local.kts
```

- [ ] **Step 2: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "gh-actions-monitor"
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
# Plugin coordinates
pluginGroup = com.example.ghactions
pluginName = gh-actions-monitor
pluginVersion = 0.1.0-SNAPSHOT

# IDE used to develop and run the plugin via :runIde
# Use IntelliJ IDEA Community for development. Marketplace listing can target PyCharm separately.
platformType = IC
platformVersion = 2024.3

# Plugin compatibility range
pluginSinceBuild = 243
pluginUntilBuild = 251.*

# Bundled plugins we depend on at build time
platformBundledPlugins = Git4Idea,org.jetbrains.plugins.github

# Toolchain
javaVersion = 17
kotlinVersion = 2.0.21
gradleVersion = 8.10.2

# Use Gradle's parallel and caching support
org.gradle.parallel = true
org.gradle.caching = true
org.gradle.jvmargs = -Xmx2g -XX:+UseG1GC
kotlin.stdlib.default.dependency = false
```

- [ ] **Step 4: Create `build.gradle.kts`**

```kotlin
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
```

> Note: the JUnit Vintage engine is included so JUnit 3-style `BasePlatformTestCase` runs alongside JUnit 5 tests. Add the dependency only if you find the engine isn't auto-resolved on your Gradle version:
> `testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")`

- [ ] **Step 5: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.10.2`
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`. If `gradle` isn't on PATH, install it via `sdkman` or your package manager first.

- [ ] **Step 6: Verify the build resolves**

Run: `./gradlew --no-daemon help`
Expected: `BUILD SUCCESSFUL`. The first invocation downloads the IntelliJ Platform artifacts (~1 GB) and may take several minutes.

- [ ] **Step 7: Commit**

```bash
git add .gitignore settings.gradle.kts gradle.properties build.gradle.kts gradlew gradlew.bat gradle/
git commit -m "chore: scaffold gradle build with intellij platform plugin"
```

---

## Task 2: Minimal `plugin.xml`

**Files:**
- Create: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the plugin descriptor**

```xml
<idea-plugin>
    <id>com.example.ghactions</id>
    <name>GitHub Actions Monitor</name>
    <vendor email="marceloduartetrevisani@gmail.com">Marcelo Trevisani</vendor>
    <description><![CDATA[
        Monitor and act on GitHub Actions for your project's repo without leaving the IDE.
        <br/>Authenticate via PAT or your IDE-configured GitHub account.
        <br/>Supports github.com and GitHub Enterprise Server.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>Git4Idea</depends>
    <depends>org.jetbrains.plugins.github</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Tool window registration is added in Task 13. -->
        <!-- Settings configurable is added in Task 11. -->
        <!-- Project services are added in their respective tasks. -->
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Verify `:buildPlugin` produces a zip**

Run: `./gradlew --no-daemon buildPlugin`
Expected: `BUILD SUCCESSFUL`. A zip appears at `build/distributions/gh-actions-monitor-0.1.0-SNAPSHOT.zip`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat: add minimal plugin descriptor"
```

---

## Task 3: `AuthSource` sealed class

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/auth/AuthSource.kt`
- Test: `src/test/kotlin/com/example/ghactions/auth/AuthSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthSourceTest {
    @Test
    fun `pat source carries token and host`() {
        val src = AuthSource.Pat(host = "https://api.github.com", token = "ghp_xxx")
        assertEquals("https://api.github.com", src.host)
        assertEquals("ghp_xxx", src.token)
    }

    @Test
    fun `ide account source carries account id and host`() {
        val src = AuthSource.IdeAccount(host = "https://api.github.com", accountId = "abc-123")
        assertEquals("https://api.github.com", src.host)
        assertEquals("abc-123", src.accountId)
    }

    @Test
    fun `pat redacts token in toString`() {
        val src = AuthSource.Pat(host = "https://api.github.com", token = "ghp_supersecret123")
        assertNotEquals(true, src.toString().contains("supersecret"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.AuthSourceTest"`
Expected: FAIL with "unresolved reference: AuthSource".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.auth

/**
 * A resolved source of GitHub credentials. Returned by [GitHubAccountResolver].
 *
 * Token values are deliberately excluded from [toString] so they cannot end up in logs.
 */
sealed class AuthSource {
    abstract val host: String

    data class Pat(
        override val host: String,
        val token: String
    ) : AuthSource() {
        override fun toString(): String = "AuthSource.Pat(host=$host, token=***)"
    }

    data class IdeAccount(
        override val host: String,
        val accountId: String
    ) : AuthSource() {
        override fun toString(): String = "AuthSource.IdeAccount(host=$host, accountId=$accountId)"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.AuthSourceTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/AuthSource.kt \
        src/test/kotlin/com/example/ghactions/auth/AuthSourceTest.kt
git commit -m "feat: add AuthSource sealed class"
```

---

## Task 4: `PluginSettings` (non-secret state)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/auth/PluginSettings.kt`
- Test: `src/test/kotlin/com/example/ghactions/auth/PluginSettingsTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register service)

- [ ] **Step 1: Write the failing test (round-trip serialization)**

```kotlin
package com.example.ghactions.auth

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class PluginSettingsTest : BasePlatformTestCase() {

    fun testDefaults() {
        val s = PluginSettings()
        assertEquals("https://api.github.com", s.state.baseUrl)
        assertNull(s.state.preferredAccountId)
        assertTrue(s.state.livePollingEnabled)
        assertEquals("FAILURES_ONLY", s.state.notificationLevel)
        assertEquals("PR_CENTRIC", s.state.viewMode)
    }

    fun testRoundTrip() {
        val original = PluginSettings()
        original.state.apply {
            baseUrl = "https://ghe.example.com/api/v3"
            preferredAccountId = "acct-7"
            livePollingEnabled = false
            notificationLevel = "OFF"
            viewMode = "TREE"
            defaultDownloadDir = "/tmp/dl"
        }

        val element = XmlSerializer.serialize(original.state)
        val restored = PluginSettings()
        XmlSerializer.deserializeInto(restored.state, element)

        assertEquals("https://ghe.example.com/api/v3", restored.state.baseUrl)
        assertEquals("acct-7", restored.state.preferredAccountId)
        assertFalse(restored.state.livePollingEnabled)
        assertEquals("OFF", restored.state.notificationLevel)
        assertEquals("TREE", restored.state.viewMode)
        assertEquals("/tmp/dl", restored.state.defaultDownloadDir)
    }

    fun testTokenFieldIsAbsent() {
        // Tokens must never be in PluginSettings — they live in PasswordSafe.
        val fields = PluginSettings.State::class.java.declaredFields.map { it.name }
        assertFalse(
            fields.any { it.lowercase().contains("token") },
            "PluginSettings.State must not contain any token field; tokens belong in PasswordSafe."
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.PluginSettingsTest"`
Expected: FAIL with "unresolved reference: PluginSettings".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Non-secret persisted plugin state. Tokens live exclusively in [PatStorage] (PasswordSafe);
 * this class must never grow a token field.
 */
@Service(Service.Level.APP)
@State(name = "GhActionsSettings", storages = [Storage("ghActionsMonitor.xml")])
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var baseUrl: String = "https://api.github.com",
        var preferredAccountId: String? = null,
        var livePollingEnabled: Boolean = true,
        var notificationLevel: String = "FAILURES_ONLY",
        var viewMode: String = "PR_CENTRIC",
        var defaultDownloadDir: String? = null
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
```

- [ ] **Step 4: Register the application service in `plugin.xml`**

Modify `src/main/resources/META-INF/plugin.xml` — replace the empty `<extensions>` block with:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.ghactions.auth.PluginSettings"/>
    </extensions>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.PluginSettingsTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/PluginSettings.kt \
        src/test/kotlin/com/example/ghactions/auth/PluginSettingsTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: add PluginSettings application service"
```

---

## Task 5: `PatStorage` (PasswordSafe wrapper)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/auth/PatStorage.kt`
- Test: `src/test/kotlin/com/example/ghactions/auth/PatStorageTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.auth

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PatStorageTest : BasePlatformTestCase() {

    fun testSetAndGet() {
        val storage = PatStorage()
        storage.setToken("https://api.github.com", "ghp_test_value")

        assertEquals("ghp_test_value", storage.getToken("https://api.github.com"))
    }

    fun testGetUnknownHostReturnsNull() {
        val storage = PatStorage()
        assertNull(storage.getToken("https://unknown.example.com/api"))
    }

    fun testClearRemovesToken() {
        val storage = PatStorage()
        storage.setToken("https://api.github.com", "ghp_temp")
        storage.clearToken("https://api.github.com")

        assertNull(storage.getToken("https://api.github.com"))
    }

    fun testTokensIsolatedPerHost() {
        val storage = PatStorage()
        storage.setToken("https://api.github.com", "ghp_dotcom")
        storage.setToken("https://ghe.example.com/api/v3", "ghp_ghes")

        assertEquals("ghp_dotcom", storage.getToken("https://api.github.com"))
        assertEquals("ghp_ghes", storage.getToken("https://ghe.example.com/api/v3"))
    }

    override fun tearDown() {
        try {
            val storage = PatStorage()
            storage.clearToken("https://api.github.com")
            storage.clearToken("https://ghe.example.com/api/v3")
        } finally {
            super.tearDown()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.PatStorageTest"`
Expected: FAIL with "unresolved reference: PatStorage".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger

/**
 * Stores GitHub PATs in IntelliJ's [PasswordSafe], keyed per host. Never logs the token.
 */
class PatStorage {
    private val log = Logger.getInstance(PatStorage::class.java)

    fun getToken(host: String): String? =
        PasswordSafe.instance.get(attributes(host))?.getPasswordAsString()

    fun setToken(host: String, token: String) {
        PasswordSafe.instance.set(attributes(host), Credentials("token", token))
        log.info("Stored PAT for host=$host (length=${token.length})")
    }

    fun clearToken(host: String) {
        PasswordSafe.instance.set(attributes(host), null)
        log.info("Cleared PAT for host=$host")
    }

    private fun attributes(host: String) =
        CredentialAttributes(generateServiceName(SERVICE_NAME, host))

    companion object {
        private const val SERVICE_NAME = "GhActionsPlugin"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.PatStorageTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/PatStorage.kt \
        src/test/kotlin/com/example/ghactions/auth/PatStorageTest.kt
git commit -m "feat: add PatStorage backed by PasswordSafe"
```

---

## Task 6: `GitHubAccountResolver`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt`
- Test: `src/test/kotlin/com/example/ghactions/auth/GitHubAccountResolverTest.kt`

> **Note on IDE-account integration.** The bundled `org.jetbrains.plugins.github` plugin's API surface for reading accounts has shifted between IDE versions. This task introduces an internal interface `IdeGithubAccountSource` so resolver logic can be tested without invoking the real IDE plugin, then provides one implementation that delegates to the bundled plugin. The delegation is intentionally thin and may need to be revisited when targeting IDE versions outside 2024.3+.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubAccountResolverTest {

    private fun fakeIdeSource(vararg accounts: Pair<String, String>) =
        object : IdeGithubAccountSource {
            override fun listAccounts() = accounts.map { (id, host) -> IdeAccountInfo(id, host) }
        }

    private fun resolver(
        ideAccounts: List<Pair<String, String>> = emptyList(),
        patHosts: Map<String, String> = emptyMap()
    ): GitHubAccountResolver {
        val pats = object : PatLookup {
            override fun getToken(host: String) = patHosts[host]
        }
        return GitHubAccountResolver(
            ideSource = fakeIdeSource(*ideAccounts.toTypedArray()),
            patLookup = pats,
            preferredAccountId = null
        )
    }

    @Test
    fun `returns null when neither source has credentials for host`() {
        val r = resolver()
        assertNull(r.resolve("https://api.github.com"))
    }

    @Test
    fun `prefers ide account when available for host`() {
        val r = resolver(
            ideAccounts = listOf("acct-1" to "https://api.github.com"),
            patHosts = mapOf("https://api.github.com" to "ghp_xxx")
        )
        val result = r.resolve("https://api.github.com")
        assertTrue(result is AuthSource.IdeAccount)
        assertEquals("acct-1", (result as AuthSource.IdeAccount).accountId)
    }

    @Test
    fun `falls back to pat when no matching ide account`() {
        val r = resolver(
            ideAccounts = listOf("acct-1" to "https://ghe.example.com/api/v3"),
            patHosts = mapOf("https://api.github.com" to "ghp_xxx")
        )
        val result = r.resolve("https://api.github.com")
        assertTrue(result is AuthSource.Pat)
        assertEquals("ghp_xxx", (result as AuthSource.Pat).token)
    }

    @Test
    fun `prefers configured account id when multiple ide accounts match host`() {
        val pats = object : PatLookup {
            override fun getToken(host: String) = null
        }
        val ideSource = object : IdeGithubAccountSource {
            override fun listAccounts() = listOf(
                IdeAccountInfo("acct-1", "https://api.github.com"),
                IdeAccountInfo("acct-2", "https://api.github.com")
            )
        }
        val r = GitHubAccountResolver(ideSource, pats, preferredAccountId = "acct-2")

        val result = r.resolve("https://api.github.com") as AuthSource.IdeAccount
        assertEquals("acct-2", result.accountId)
    }

    @Test
    fun `host comparison is case insensitive and normalized`() {
        val r = resolver(
            ideAccounts = listOf("acct-1" to "https://API.github.com")
        )
        val result = r.resolve("https://api.github.com")
        assertTrue(result is AuthSource.IdeAccount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.GitHubAccountResolverTest"`
Expected: FAIL with unresolved references.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.auth

/** Anything that can list IDE-configured GitHub accounts. Pulled out for testability. */
interface IdeGithubAccountSource {
    fun listAccounts(): List<IdeAccountInfo>
}

data class IdeAccountInfo(val id: String, val host: String)

/** Anything that can look up a PAT for a host. Pulled out for testability. */
interface PatLookup {
    fun getToken(host: String): String?
}

/**
 * Resolves credentials for a target [host] in priority order:
 * 1. IDE-configured GitHub account whose host matches.
 *    If multiple match, prefer the one whose id == [preferredAccountId]; otherwise the first.
 * 2. PAT stored in PasswordSafe for that host.
 * 3. null — caller should show empty state.
 */
class GitHubAccountResolver(
    private val ideSource: IdeGithubAccountSource,
    private val patLookup: PatLookup,
    private val preferredAccountId: String?
) {

    fun resolve(host: String): AuthSource? {
        val normalizedTarget = normalize(host)

        val matchingIdeAccounts = ideSource.listAccounts()
            .filter { normalize(it.host) == normalizedTarget }

        if (matchingIdeAccounts.isNotEmpty()) {
            val chosen = matchingIdeAccounts.firstOrNull { it.id == preferredAccountId }
                ?: matchingIdeAccounts.first()
            return AuthSource.IdeAccount(host = host, accountId = chosen.id)
        }

        patLookup.getToken(host)?.let {
            return AuthSource.Pat(host = host, token = it)
        }

        return null
    }

    private fun normalize(url: String): String =
        url.trim().lowercase().trimEnd('/')
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.auth.GitHubAccountResolverTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt \
        src/test/kotlin/com/example/ghactions/auth/GitHubAccountResolverTest.kt
git commit -m "feat: add GitHubAccountResolver with IDE-first then PAT priority"
```

---

## Task 7: Production `IdeGithubAccountSource` adapter

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt` (add adapter at bottom of file)

> This task wires the resolver to the real bundled GitHub plugin. It has no automated test because the API requires a live IDE; we cover it via the manual smoke test in Task 14.

- [ ] **Step 1: Append the adapter**

Add to the bottom of `GitHubAccountResolver.kt`:

```kotlin
/**
 * Adapter that reads accounts from the bundled `org.jetbrains.plugins.github` plugin.
 *
 * The bundled plugin's account API has changed across IDE versions; this adapter is
 * deliberately defensive — if the call throws (API mismatch, plugin disabled), it logs
 * and returns an empty list so that the user can still fall back to PAT auth.
 */
class BundledGithubAccountSource : IdeGithubAccountSource {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(BundledGithubAccountSource::class.java)

    override fun listAccounts(): List<IdeAccountInfo> = try {
        val mgr = org.jetbrains.plugins.github.authentication.accounts.GHAccountManager.getInstance()
        mgr.accountsState.value.map { acct ->
            IdeAccountInfo(
                id = acct.id,
                host = acct.server.toApiUrl()
            )
        }
    } catch (e: Throwable) {
        log.warn("Failed to read IDE GitHub accounts; falling back to empty list", e)
        emptyList()
    }

    private fun org.jetbrains.plugins.github.api.GithubServerPath.toApiUrl(): String =
        toApiUrl().removeSuffix("/")
}
```

> **If `toApiUrl()` doesn't exist on the version you're targeting**, substitute `toUrl()` and append `/api/v3` for non-github.com hosts. Verify against `GithubServerPath` in the bundled plugin source: open `External Libraries → org.jetbrains.plugins.github` in the IDE.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt
git commit -m "feat: add bundled GitHub plugin adapter for ide accounts"
```

---

## Task 8: `events/Topics.kt` — MessageBus topic constants

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/events/Topics.kt`

> No automated test — these are constants. Subscriber/publisher behavior is exercised once we add code that uses them.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.ghactions.events

import com.intellij.util.messages.Topic

/** Event signaling that the resolved authentication source has changed. */
fun interface AuthChangedListener {
    fun onAuthChanged()
}

/** Event signaling that the project's bound GitHub repo has changed (or become null). */
fun interface RepoBindingChangedListener {
    fun onRepoBindingChanged(newBinding: BoundRepo?)
}

/** Snapshot of a repo binding, published with [RepoBindingChangedListener]. */
data class BoundRepo(
    val host: String,        // e.g. "https://api.github.com"
    val owner: String,
    val repo: String
)

object Topics {
    val AUTH_CHANGED: Topic<AuthChangedListener> =
        Topic.create("GhActions.AuthChanged", AuthChangedListener::class.java)

    val REPO_BINDING_CHANGED: Topic<RepoBindingChangedListener> =
        Topic.create("GhActions.RepoBindingChanged", RepoBindingChangedListener::class.java)

    // RateLimitChanged topic added in Plan 2 alongside the HTTP client.
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/events/Topics.kt
git commit -m "feat: add MessageBus topics for auth and repo binding changes"
```

---

## Task 9: `RepoBinding` project service

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/repo/RepoBinding.kt`
- Test: `src/test/kotlin/com/example/ghactions/repo/RepoBindingTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register service)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.events.BoundRepo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RepoBindingTest : BasePlatformTestCase() {

    fun testParseGithubHttpsUrl() {
        val parsed = RepoBinding.parseRemote("https://github.com/octocat/Hello-World.git")
        assertEquals(BoundRepo(host = "https://api.github.com", owner = "octocat", repo = "Hello-World"), parsed)
    }

    fun testParseGithubSshUrl() {
        val parsed = RepoBinding.parseRemote("git@github.com:octocat/Hello-World.git")
        assertEquals(BoundRepo(host = "https://api.github.com", owner = "octocat", repo = "Hello-World"), parsed)
    }

    fun testParseGithubHttpsUrlWithoutDotGit() {
        val parsed = RepoBinding.parseRemote("https://github.com/octocat/Hello-World")
        assertEquals(BoundRepo(host = "https://api.github.com", owner = "octocat", repo = "Hello-World"), parsed)
    }

    fun testParseEnterpriseHttpsUrl() {
        val parsed = RepoBinding.parseRemote("https://ghe.example.com/team/service.git")
        assertEquals(
            BoundRepo(host = "https://ghe.example.com/api/v3", owner = "team", repo = "service"),
            parsed
        )
    }

    fun testParseEnterpriseSshUrl() {
        val parsed = RepoBinding.parseRemote("git@ghe.example.com:team/service.git")
        assertEquals(
            BoundRepo(host = "https://ghe.example.com/api/v3", owner = "team", repo = "service"),
            parsed
        )
    }

    fun testParseRejectsNonGithubHosts() {
        assertNull(RepoBinding.parseRemote("https://gitlab.com/foo/bar.git"))
        assertNull(RepoBinding.parseRemote("git@bitbucket.org:foo/bar.git"))
    }

    fun testParseRejectsMalformedInput() {
        assertNull(RepoBinding.parseRemote(""))
        assertNull(RepoBinding.parseRemote("not-a-url"))
        assertNull(RepoBinding.parseRemote("https://github.com/onlyone"))
    }

    fun testCurrentIsNullWhenProjectHasNoGitRemotes() {
        val binding = project.getService(RepoBinding::class.java)
        // Test fixture project has no git remotes by default
        assertNull(binding.current)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.repo.RepoBindingTest"`
Expected: FAIL with "unresolved reference: RepoBinding".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.events.BoundRepo
import com.example.ghactions.events.Topics
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Resolves the open project's bound GitHub repo from its `origin` git remote and republishes
 * changes via [Topics.REPO_BINDING_CHANGED]. Listens for git remote/repository changes via
 * the platform's [VcsRepositoryMappingListener].
 */
@Service(Service.Level.PROJECT)
class RepoBinding(private val project: Project) : Disposable {

    private val log = Logger.getInstance(RepoBinding::class.java)

    @Volatile
    var current: BoundRepo? = null
        private set

    init {
        recompute()
        project.messageBus.connect(this).subscribe(
            VcsRepositoryMappingListener.MAPPING_CHANGED,
            VcsRepositoryMappingListener { recompute() }
        )
    }

    fun recompute() {
        val previous = current
        val next = computeBoundRepo()
        current = next
        if (previous != next) {
            log.info("Repo binding changed: $previous -> $next")
            project.messageBus.syncPublisher(Topics.REPO_BINDING_CHANGED).onRepoBindingChanged(next)
        }
    }

    private fun computeBoundRepo(): BoundRepo? {
        val mgr = GitRepositoryManager.getInstance(project)
        val repo = mgr.repositories.firstOrNull() ?: return null
        val origin = repo.remotes.firstOrNull { it.name == "origin" }
            ?: repo.remotes.firstOrNull()
            ?: return null
        val firstUrl = origin.urls.firstOrNull() ?: return null
        return parseRemote(firstUrl)
    }

    override fun dispose() = Unit

    companion object {
        // Matches: https://host/owner/repo[.git] or git@host:owner/repo[.git]
        private val HTTPS_RE = Regex("^https?://([^/]+)/([^/]+)/([^/.]+?)(?:\\.git)?/?$")
        private val SSH_RE = Regex("^git@([^:]+):([^/]+)/([^/.]+?)(?:\\.git)?/?$")

        /**
         * Parses a git remote URL and returns the bound repo, or null if the URL is not GitHub-shaped
         * or is malformed. Public for testability.
         */
        fun parseRemote(url: String): BoundRepo? {
            val (host, owner, repo) = parseToTriple(url) ?: return null
            val apiHost = when {
                host.equals("github.com", ignoreCase = true) -> "https://api.github.com"
                else -> "https://$host/api/v3"
            }
            // Reject hosts that obviously aren't GitHub. Conservative deny-list; users with custom
            // GitHub Enterprise hostnames will still work because their hosts won't be on this list.
            if (host.equals("gitlab.com", ignoreCase = true)) return null
            if (host.equals("bitbucket.org", ignoreCase = true)) return null
            return BoundRepo(host = apiHost, owner = owner, repo = repo)
        }

        private fun parseToTriple(url: String): Triple<String, String, String>? {
            HTTPS_RE.matchEntire(url)?.let { m ->
                return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }
            SSH_RE.matchEntire(url)?.let { m ->
                return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }
            return null
        }
    }
}
```

- [ ] **Step 4: Register the project service in `plugin.xml`**

Modify `src/main/resources/META-INF/plugin.xml` — extend the `<extensions>` block so it reads:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.ghactions.auth.PluginSettings"/>
        <projectService serviceImplementation="com.example.ghactions.repo.RepoBinding"/>
    </extensions>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.repo.RepoBindingTest"`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RepoBinding.kt \
        src/test/kotlin/com/example/ghactions/repo/RepoBindingTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: add RepoBinding project service derived from git origin"
```

---

## Task 10: `TestConnection` helper (single GET /user probe)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/auth/TestConnection.kt`

> No unit test — this performs a real HTTP call. Behavior is verified manually in Task 14. We add a fuller test harness in Plan 2 once `GitHubClient` and `MockWebServer` exist.

- [ ] **Step 1: Create the helper**

```kotlin
package com.example.ghactions.auth

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Verifies that a credential can call `GET /user` against a host. Returns either the
 * authenticated user's login or a structured failure. Single, synchronous HTTP call —
 * intended only for the settings panel's "Test connection" button. Plan 2 replaces this
 * with the proper `GitHubClient` implementation.
 */
object TestConnection {

    private val log = Logger.getInstance(TestConnection::class.java)

    sealed class Result {
        data class Success(val login: String) : Result()
        data class Failure(val httpStatus: Int?, val message: String) : Result()
    }

    fun probe(baseUrl: String, token: String): Result {
        val url = URL(baseUrl.trimEnd('/') + "/user")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "gh-actions-monitor/0.1")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val login = LOGIN_RE.find(body)?.groupValues?.get(1)
                if (login != null) Result.Success(login)
                else Result.Failure(code, "Connected but response had no 'login' field")
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Result.Failure(code, "HTTP $code: ${err.take(200)}")
            }
        } catch (e: Exception) {
            log.warn("Test connection failed for $baseUrl", e)
            Result.Failure(null, e.message ?: e::class.java.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    // Tiny inline regex avoids pulling in a JSON library in Plan 1.
    private val LOGIN_RE = Regex(""""login"\s*:\s*"([^"]+)"""")

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/TestConnection.kt
git commit -m "feat: add TestConnection helper for settings probe"
```

---

## Task 11: Settings UI — `GhActionsSettingsPanel` and `GhActionsConfigurable`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/settings/GhActionsSettingsPanel.kt`
- Create: `src/main/kotlin/com/example/ghactions/settings/GhActionsConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register configurable)

> No automated test — UI panels are smoke-tested manually in Task 14. The fields' modification semantics are covered by `Configurable.isModified`, which we delegate to a simple equals comparison.

- [ ] **Step 1: Create the Swing panel**

```kotlin
package com.example.ghactions.settings

import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.IdeAccountInfo
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.auth.TestConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPasswordField

/**
 * The Swing form. Only contains UI; persistence wiring happens in [GhActionsConfigurable].
 */
class GhActionsSettingsPanel {

    private val state = PluginSettings.getInstance().state.copy()
    private val patStorage = PatStorage()
    private val ideAccountSource = BundledGithubAccountSource()

    // Token is held in the form only while editing; on apply() it's written to PasswordSafe and cleared from memory.
    private var pendingToken: String? = null

    private val tokenField = JPasswordField(40)
    private val statusLabel = JBLabel(" ")

    // IDE accounts dropdown. The first entry is a sentinel meaning "(none — use token below)".
    private data class AccountChoice(val id: String?, val label: String) {
        override fun toString(): String = label
    }
    private val accountChoices: List<AccountChoice> = buildList {
        add(AccountChoice(id = null, label = "(none — use token below)"))
        ideAccountSource.listAccounts().forEach { acct: IdeAccountInfo ->
            add(AccountChoice(id = acct.id, label = "${acct.id} @ ${acct.host}"))
        }
    }
    private val accountCombo: JComboBox<AccountChoice> = JComboBox(DefaultComboBoxModel(accountChoices.toTypedArray())).apply {
        selectedIndex = accountChoices.indexOfFirst { it.id == state.preferredAccountId }
            .takeIf { it >= 0 } ?: 0
    }

    val component: DialogPanel = panel {
        group("Connection") {
            row("Base URL:") {
                textField()
                    .bindText(state::baseUrl)
                    .columns(40)
                    .comment("Use https://api.github.com for github.com, or https://&lt;host&gt;/api/v3 for GitHub Enterprise Server.")
            }
            row("Use IDE-configured GitHub account:") {
                cell(accountCombo).comment(
                    "Reads accounts from the bundled GitHub plugin. Pick one to skip the token field below."
                )
            }
            row("Personal access token:") {
                cell(tokenField).comment(
                    "Used only when no IDE account is selected above. Stored in the IDE's secure password storage."
                )
            }
            row {
                button("Test connection") { testConnection() }
                cell(statusLabel)
            }
        }
        group("Behavior") {
            row {
                checkBox("Live polling enabled").bindSelected(state::livePollingEnabled)
            }
            row("Notification level:") {
                segmentedButton(listOf("OFF", "FAILURES_ONLY", "ALL")) { text = it }
                    .bind({ state.notificationLevel }, { state.notificationLevel = it })
            }
            row("Default view mode:") {
                segmentedButton(listOf("PR_CENTRIC", "TABBED", "TREE")) { text = it }
                    .bind({ state.viewMode }, { state.viewMode = it })
            }
            row("Default download directory:") {
                textField()
                    .bindText({ state.defaultDownloadDir.orEmpty() }, { state.defaultDownloadDir = it.ifBlank { null } })
                    .columns(40)
            }
        }
    }

    fun isModified(): Boolean {
        val saved = PluginSettings.getInstance().state
        // Sync the combo selection into [state] before comparing.
        state.preferredAccountId = (accountCombo.selectedItem as? AccountChoice)?.id
        if (state != saved) return true
        if (pendingTokenChanged()) return true
        return false
    }

    fun apply() {
        state.preferredAccountId = (accountCombo.selectedItem as? AccountChoice)?.id
        ApplicationManager.getApplication().runWriteAction {
            PluginSettings.getInstance().loadState(state.copy())
        }
        pendingToken?.let { newToken ->
            if (newToken.isBlank()) patStorage.clearToken(state.baseUrl)
            else patStorage.setToken(state.baseUrl, newToken)
            pendingToken = null
            tokenField.text = ""
        }
    }

    fun reset() {
        val saved = PluginSettings.getInstance().state
        // Copy fields back into [state]
        state.baseUrl = saved.baseUrl
        state.preferredAccountId = saved.preferredAccountId
        state.livePollingEnabled = saved.livePollingEnabled
        state.notificationLevel = saved.notificationLevel
        state.viewMode = saved.viewMode
        state.defaultDownloadDir = saved.defaultDownloadDir

        accountCombo.selectedIndex = accountChoices.indexOfFirst { it.id == state.preferredAccountId }
            .takeIf { it >= 0 } ?: 0
        tokenField.text = ""
        pendingToken = null
        statusLabel.text = " "
        component.reset()
    }

    private fun pendingTokenChanged(): Boolean {
        val typed = String(tokenField.password)
        return typed.isNotEmpty().also { pendingToken = if (it) typed else null }
    }

    private fun testConnection() {
        val typed = String(tokenField.password)
        val token = typed.ifEmpty { patStorage.getToken(state.baseUrl) ?: "" }
        if (token.isEmpty()) {
            statusLabel.text = "Enter a token first."
            return
        }
        statusLabel.text = "Testing…"
        Thread {
            val result = TestConnection.probe(state.baseUrl, token)
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = when (result) {
                    is TestConnection.Result.Success -> "Connected as @${result.login}"
                    is TestConnection.Result.Failure -> "Failed: ${result.message}"
                }
            }
        }.start()
    }
}
```

> **About `segmentedButton(...)` `.bind`:** depending on the platform version, the binding API may use different overloads. If the snippet doesn't compile, replace each `.bind({ getter }, { setter(...) })` with manual `actionListener` wiring. The form intent is to round-trip three string-valued radio choices; the exact API call is allowed to vary.

- [ ] **Step 2: Create the `Configurable` adapter**

```kotlin
package com.example.ghactions.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class GhActionsConfigurable : Configurable {
    private var panel: GhActionsSettingsPanel? = null

    override fun getDisplayName(): String = "GitHub Actions Monitor"

    override fun createComponent(): JComponent {
        val p = GhActionsSettingsPanel()
        panel = p
        return p.component
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
```

- [ ] **Step 3: Register the configurable in `plugin.xml`**

Modify `src/main/resources/META-INF/plugin.xml` — extend the `<extensions>` block:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.ghactions.auth.PluginSettings"/>
        <projectService serviceImplementation="com.example.ghactions.repo.RepoBinding"/>
        <applicationConfigurable
            id="com.example.ghactions.settings"
            displayName="GitHub Actions Monitor"
            instance="com.example.ghactions.settings.GhActionsConfigurable"
            parentId="tools"/>
    </extensions>
```

- [ ] **Step 4: Verify build**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Plugin zip rebuilt.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/settings/ \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: add settings panel under tools menu"
```

---

## Task 12: `EmptyStatePanel`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/EmptyStatePanel.kt`

> No automated test — visual state is smoke-tested in Task 14.

- [ ] **Step 1: Create the panel**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.auth.GitHubAccountResolver
import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.auth.PatLookup
import com.example.ghactions.events.BoundRepo
import com.example.ghactions.events.Topics
import com.example.ghactions.repo.RepoBinding
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The only UI panel in Plan 1. Renders one of four empty states:
 *  1. No GitHub remote in this project's git config.
 *  2. Git remote is GitHub-shaped but no credentials are configured.
 *  3. Both present — show "Connected to <owner>/<repo> as <auth-source>".
 *  4. (Future plans) Will replace state 3 with the actual run list.
 *
 * Responds to both [Topics.REPO_BINDING_CHANGED] and [Topics.AUTH_CHANGED].
 */
class EmptyStatePanel(private val project: Project) : JPanel() {

    private val title = JBLabel().apply { font = font.deriveFont(font.size + 4f) }
    private val detail = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val actionLink = ActionLink("Open Settings…") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub Actions Monitor")
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(20)
        add(title)
        add(JBUI.Borders.emptyTop(8).let { javax.swing.Box.createVerticalStrut(8) })
        add(detail)
        add(javax.swing.Box.createVerticalStrut(12))
        add(actionLink)

        refresh()

        val conn = project.messageBus.connect()
        conn.subscribe(Topics.REPO_BINDING_CHANGED, com.example.ghactions.events.RepoBindingChangedListener { refresh() })
        conn.subscribe(Topics.AUTH_CHANGED, com.example.ghactions.events.AuthChangedListener { refresh() })
    }

    private fun refresh() {
        val binding = project.getService(RepoBinding::class.java).current
        when {
            binding == null -> renderNoRepo()
            !hasCredentials(binding) -> renderNoCreds(binding)
            else -> renderConnected(binding)
        }
        revalidate()
        repaint()
    }

    private fun renderNoRepo() {
        title.text = "No GitHub repo detected"
        detail.text = "<html>This project's git remote isn't a GitHub repo (or the project has no git remotes).</html>"
        actionLink.isVisible = false
    }

    private fun renderNoCreds(b: BoundRepo) {
        title.text = "Connect to ${b.owner}/${b.repo}"
        detail.text = "<html>No credentials configured for ${b.host}.</html>"
        actionLink.isVisible = true
    }

    private fun renderConnected(b: BoundRepo) {
        title.text = "Connected to ${b.owner}/${b.repo}"
        detail.text = "<html>Run data will appear here once Plan 2 lands.</html>"
        actionLink.isVisible = false
    }

    private fun hasCredentials(b: BoundRepo): Boolean {
        val settings = PluginSettings.getInstance().state
        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        return resolver.resolve(b.host) != null
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/EmptyStatePanel.kt
git commit -m "feat: add empty-state tool window panel"
```

---

## Task 13: `GhActionsToolWindowFactory` and registration

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`
- Test: `src/test/kotlin/com/example/ghactions/ui/ToolWindowFactoryTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register tool window)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.ui

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolWindowFactoryTest : BasePlatformTestCase() {

    fun testToolWindowRegistered() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(GhActionsToolWindowFactory.ID)
        assertNotNull("Tool window 'GitHubActions' must be registered by plugin.xml", tw)
    }

    fun testToolWindowFactoryCreatesContentWithoutThrowing() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(GhActionsToolWindowFactory.ID)!!
        GhActionsToolWindowFactory().createToolWindowContent(project, tw)
        assertTrue(tw.contentManager.contentCount >= 1)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.ui.ToolWindowFactoryTest"`
Expected: FAIL with unresolved reference or "tool window 'GitHubActions' must be registered".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GhActionsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EmptyStatePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, /* displayName = */ "", /* isLockable = */ false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "GitHubActions"
    }
}
```

- [ ] **Step 4: Register the tool window in `plugin.xml`**

Modify `src/main/resources/META-INF/plugin.xml` — extend the `<extensions>` block to its final form:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.ghactions.auth.PluginSettings"/>
        <projectService serviceImplementation="com.example.ghactions.repo.RepoBinding"/>
        <applicationConfigurable
            id="com.example.ghactions.settings"
            displayName="GitHub Actions Monitor"
            instance="com.example.ghactions.settings.GhActionsConfigurable"
            parentId="tools"/>
        <toolWindow
            id="GitHubActions"
            anchor="right"
            icon="AllIcons.Vcs.Vendors.Github"
            factoryClass="com.example.ghactions.ui.GhActionsToolWindowFactory"/>
    </extensions>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.example.ghactions.ui.ToolWindowFactoryTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt \
        src/test/kotlin/com/example/ghactions/ui/ToolWindowFactoryTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: register GitHub Actions tool window"
```

---

## Task 14: Manual smoke test (`./gradlew runIde`)

**Files:** none.

> No automated equivalent — this validates the end-to-end plugin behavior in a live IDE. Spend ~10 minutes here. If anything fails, file a follow-up task in Plan 2 *unless* the failure is in functionality this plan was supposed to deliver, in which case fix it now.

- [ ] **Step 1: Launch a development IDE**

Run: `./gradlew --no-daemon runIde`
Expected: a sandbox IntelliJ IDEA Community window opens within ~30 seconds.

- [ ] **Step 2: Open a non-GitHub project**

In the dev IDE: open any project that has no git remote (e.g., create a new empty project).
Expected: the *GitHub Actions* tool window appears on the right gutter; clicking it shows the "No GitHub repo detected" empty state.

- [ ] **Step 3: Open a GitHub-cloned project, no credentials**

Either clone a public GitHub repo or open one you already have. Make sure no PAT is in settings and no IDE GitHub account is configured for that host.
Expected: tool window shows "Connect to <owner>/<repo>" with an "Open Settings…" link.

- [ ] **Step 4: Add a PAT via settings**

Click *Open Settings…*. In the dialog: enter `https://api.github.com` (default), paste a PAT with `repo` + `workflow` scopes (or use a scratch test PAT). Click *Test connection*.
Expected: status label flips to "Connected as @&lt;your-login&gt;" within a few seconds. Click *OK*. Tool window updates to "Connected to <owner>/<repo>".

- [ ] **Step 5: Restart the dev IDE**

Stop the runIde process; re-run `./gradlew --no-daemon runIde`. Re-open the same project.
Expected: settings persist (base URL, polling toggle, view mode); the PAT is still present (visible by clicking *Test connection* without entering anything — it should reuse the stored token).

- [ ] **Step 6: Verify rejection of non-GitHub remotes**

Open a project whose `origin` points at GitLab or Bitbucket.
Expected: tool window shows "No GitHub repo detected" (the plugin doesn't try to treat it as GitHub).

- [ ] **Step 7: Document any deviations**

If any of the above didn't behave as expected, write a short note at the bottom of this plan under a new "Smoke test deviations" section. We'll fold the fixes into Plan 2's preamble.

- [ ] **Step 8: Commit any documentation changes**

```bash
git add docs/superpowers/plans/2026-04-28-foundation.md
git commit -m "docs: record smoke test results for foundation plan"
```

If there were no deviations, skip the commit.

---

## Task 15: Final sweep — run all tests, build the plugin zip

**Files:** none.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon test`
Expected: PASS. Total test count from this plan: 22 (3 + 3 + 4 + 5 + 8 + 2; some platform tests count as one each — actual number may vary by ±2 depending on parameterization).

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Zip at `build/distributions/gh-actions-monitor-0.1.0-SNAPSHOT.zip`.

- [ ] **Step 3: Plugin verifier (catches incompatibilities with target IDE range)**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. Warnings about "experimental API" or "internal API" usage are acceptable for v1; failures must be fixed.

- [ ] **Step 4: Commit any small fixups required by the verifier**

If the verifier flags issues, fix them, re-run, and commit:

```bash
git commit -am "fix: address plugin verifier findings"
```

If clean, skip.

---

## Plan-level verification (run after Task 15)

Confirm Plan 1 is done:

- [ ] All 15 tasks have green check-marks above.
- [ ] `./gradlew test` passes.
- [ ] `./gradlew buildPlugin` produces a zip.
- [ ] `./gradlew verifyPlugin` passes.
- [ ] Manual smoke test (Task 14) all green.
- [ ] `git log --oneline | head -20` shows the expected commit sequence.

---

## What ships at the end of this plan

A working IntelliJ Platform plugin that:

1. Installs into IDEA Community 2024.3+ / PyCharm 2024.3+.
2. Registers a *GitHub Actions* tool window on the right gutter.
3. Detects whether the open project's git origin points at GitHub (github.com or GHES).
4. Stores PAT credentials in `PasswordSafe`, persists non-secret settings across IDE restarts.
5. Resolves credentials in priority order (IDE-configured GitHub account → plugin PAT) and surfaces the chosen source.
6. Verifies a credential via a single `GET /user` test-connection probe.
7. Renders one of three empty states based on (repo present? credentials present?).
8. Republishes binding changes and auth changes on dedicated `MessageBus` topics so later plans can subscribe without coupling.

What it does **not** yet do:
- List runs, fetch logs, or make any API calls beyond the test-connection probe.
- Have a polling coordinator or rate-limit handling.
- Expose filters, view modes, annotations, summaries, artifacts, or write actions.
- Show a status bar widget or notifications.

These are the deliverables of Plans 2–9.

---

## Open questions / risks

1. **`GHAccountManager` API drift** — the bundled GitHub plugin's account API has shifted across IDE versions. Task 7 wraps the call defensively (catch + log + fallback). If running against IDE versions outside 2024.3+, audit the API names.
2. **`segmentedButton(...).bind(...)` API** — the Kotlin UI DSL's binding overloads have been refactored multiple times. Task 11 includes a fallback note. Watch for compilation errors there.
3. **`junit-vintage-engine` resolution** — depending on Gradle/JUnit version interactions, the engine may need to be added as an explicit `testRuntimeOnly` dependency. Task 1 includes a note.
4. **Plugin verifier warnings** — the IntelliJ Platform Gradle plugin 2.x's `pluginVerification.ides { recommended() }` runs the verifier against recent IDE versions. New API surface added by JetBrains can show as "internal API usage" warnings. They're acceptable for v1; act on actual failures.
