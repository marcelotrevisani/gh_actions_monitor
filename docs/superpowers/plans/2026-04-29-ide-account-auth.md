# Plan 5 — Complete IDE-Account Authentication

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the IDE-account authentication path that Plan 1 stubbed out: when the user picks an IDE-configured GitHub account in our settings, the plugin actually fetches that account's token via `GHAccountManager.findCredentials(...)` and uses it for every API call. Add an *Add / manage GitHub accounts…* button in our settings that jumps to IntelliJ's Version Control → GitHub page (the bundled GitHub plugin handles the github.com OAuth flow there). Add an auth status indicator that names the source currently in use.

**Architecture:** Thread a `suspend` boundary through `clientFactory: suspend () -> GitHubClient?` so the factory can call the bundled plugin's `findCredentials(account)` (a `suspend` function). Extend `IdeGithubAccountSource` with a `suspend findToken(accountId)` method and add a matching wrapper in `BundledGithubAccountSource`. Add a `GitHubAccountResolver.resolveAuth(host)` (suspend) that returns the `AuthSource` plus the resolved token. `ProductionClientFactory.create(project)` becomes `suspend` and uses the resolver to build `GitHubClient` for both PAT and IDE-account paths. UI changes in `GhActionsSettingsPanel`: a button delegating to `ShowSettingsUtil` for the GitHub configurable, and a status label that shows the active auth source after each *Test connection* roundtrip.

**Tech Stack:** Same as Plans 1–4 — Kotlin 2.0.21, Ktor 2.3.13 with kotlinx-coroutines `compileOnly`, kotlinx-serialization 1.7.3, JUnit 5 + MockK + Ktor MockEngine for tests, IntelliJ Platform 2024.3.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered:
- *Authentication & settings → Account resolution order → 1. IDE-configured GitHub account*.
- *Components → `GitHubAccountResolver`* — gains a `resolveAuth` suspend overload.
- *Components → settings panel — "Use IDE-configured GitHub account" dropdown* — already wired in Plan 1; this plan makes it functional.

**Sections deliberately deferred:**
- Live polling, rate-limit awareness — **Plan 6**.
- Standalone github.com OAuth device flow (instead of routing through the bundled plugin) — **Plan 7** if still wanted; most users will be served by Plan 5's path.
- ANSI color rendering — **Plan 8**.
- Notifications + status bar widget — **Plan 9+**.

**Plans 1–4 → Plan 5 carry-overs (lessons that still apply):**
- `kotlinx-coroutines-core` stays **`compileOnly`** with `excludeCoroutines()` on every Ktor `implementation`. This plan adds suspend boundaries — they all run on the platform-provided coroutines, never on a duplicate.
- Production `clientFactory` runs on `Dispatchers.IO` (via the repository's scope). Tests inject `Dispatchers.Unconfined`. **Don't** flip the production default again.
- Tool-window panels still register with the `Content`'s disposer in `GhActionsToolWindowFactory`. Plan 5 doesn't add new panels but shouldn't disturb that wiring.
- `service<X>()` (kotlin extension), not `X.getInstance()`.
- The two existing `clientFactory` users (`RunRepository`, `PullRequestRepository`) take a `() -> GitHubClient?` lambda today. Kotlin allows passing a regular lambda where a `suspend` lambda is expected, so changing those parameter types to `suspend () -> GitHubClient?` is backward-compatible at the test injection sites — existing `clientFactory = { client }` lambdas keep working unchanged.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   └── kotlin/com/example/ghactions/
    │       ├── auth/
    │       │   └── GitHubAccountResolver.kt          (modify — add findToken; add resolveAuth)
    │       ├── repo/
    │       │   ├── RunRepository.kt                  (modify — clientFactory becomes suspend; ProductionClientFactory.create becomes suspend)
    │       │   ├── PullRequestRepository.kt          (modify — clientFactory becomes suspend)
    │       │   └── ProductionClientFactory.kt        (new — extracted from RunRepository.kt for visibility + suspend signature)
    │       └── settings/
    │           └── GhActionsSettingsPanel.kt         (modify — Manage Accounts button + status label)
    └── test/
        └── kotlin/com/example/ghactions/
            ├── auth/
            │   └── GitHubAccountResolverTest.kt      (modify — add resolveAuth tests; existing tests stay)
            ├── repo/
            │   ├── RunRepositoryTest.kt              (modify if any test fixture breaks; expected to be a no-op)
            │   ├── RunRepositoryArchiveTest.kt       (modify if fixture breaks; expected no-op)
            │   └── PullRequestRepositoryTest.kt      (modify if fixture breaks; expected no-op)
```

Files **deleted**: none. The `ProductionClientFactory` extraction in Task 4 is a *move* — its existing definition at the bottom of `RunRepository.kt` gets replaced by an `import` of the new top-level file. Same internal name, same contract, just visible from a place that's easier to test and less crowded.

**File responsibility notes:**
- `ProductionClientFactory.kt` deserves its own file now: it crosses three packages (`auth`, `api`, `repo`) and grows a `suspend` body in this plan. Keeping it in `RunRepository.kt` made sense when the factory was three lines; with the credential lookup added it's worth its own home.
- `GitHubAccountResolver.kt` keeps both the resolver class and the `BundledGithubAccountSource` adapter (the existing structure from Plan 1). The new `resolveAuth` method is a sibling of the existing `resolve(host)` — both stay.
- The settings panel grows by one row in the *Connection* group (the *Add / manage* button + status label). No structural reshuffling.

---

## Conventions

- **Tests** stay JUnit 5 (`@Test`, `kotlin.test.*`) for pure logic; `BasePlatformTestCase` only when we need a real `Project`. The `resolveAuth` tests are pure — no platform.
- **Suspend boundaries** are minimal: only the credential-lookup path. UI stays on the EDT; HTTP keeps its own `Dispatchers.IO`.
- **`clientFactory` change** ripples to two repository call sites (one each in `RunRepository.refreshRuns/refreshJobs/refreshLogs/refreshStepLog` — all already inside `scope.launch`, so the suspend call is fine) and one in `PullRequestRepository.refreshPullRequests`. No new test code for the rippled changes; existing repository tests verify behaviour is unchanged.
- **Commits** one per task, type-prefixed message, no `Co-Authored-By` trailer.

---

## Task 1: Extend `IdeGithubAccountSource` with `findToken`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt`

The interface gets a new `suspend` method `findToken(accountId)`. The `BundledGithubAccountSource` adapter implements it via `GHAccountManager.findCredentials(account)`. We give the interface a default implementation (`= null`) so existing test stubs that only override `listAccounts()` keep compiling.

`GHAccountManager.findCredentials(account: GithubAccount): String?` is a suspend function. The adapter wraps it defensively (try/catch + warn) — same pattern as `listAccounts()` from Plan 1.

- [ ] **Step 1: Read the existing file**

The file currently has:
```kotlin
interface IdeGithubAccountSource {
    fun listAccounts(): List<IdeAccountInfo>
}

data class IdeAccountInfo(val id: String, val host: String)

interface PatLookup {
    fun getToken(host: String): String?
}

class GitHubAccountResolver(/* ... */) {
    fun resolve(host: String): AuthSource? { /* ... */ }
}

class BundledGithubAccountSource : IdeGithubAccountSource {
    override fun listAccounts(): List<IdeAccountInfo> = try { /* ... */ } catch (...) { /* ... */ }
}
```

You're modifying two of these declarations.

- [ ] **Step 2: Extend the interface**

Replace the existing `interface IdeGithubAccountSource` declaration with:

```kotlin
/** Anything that can list IDE-configured GitHub accounts and look up their tokens. */
interface IdeGithubAccountSource {
    fun listAccounts(): List<IdeAccountInfo>

    /**
     * Returns the persistent token for the account identified by [accountId], or null if
     * the account doesn't exist, has no credentials stored, or the lookup fails.
     *
     * Default returns null — convenient for tests that only need the listing surface
     * (the resolver still works in PAT-only mode when this returns null).
     */
    suspend fun findToken(accountId: String): String? = null
}
```

- [ ] **Step 3: Implement the lookup in `BundledGithubAccountSource`**

In the same file, find the existing `class BundledGithubAccountSource : IdeGithubAccountSource { override fun listAccounts(): List<IdeAccountInfo> = try { ... } catch (...) { ... } }`. Add a new `findToken` override after `listAccounts`:

```kotlin
    override suspend fun findToken(accountId: String): String? = try {
        val mgr = com.intellij.openapi.components.service<
            org.jetbrains.plugins.github.authentication.accounts.GHAccountManager>()
        val account = mgr.accountsState.value.firstOrNull { it.id == accountId }
        if (account == null) {
            log.warn("findToken: no IDE GitHub account with id=$accountId")
            null
        } else {
            mgr.findCredentials(account)
        }
    } catch (e: Throwable) {
        log.warn("findToken: lookup failed for id=$accountId", e)
        null
    }
```

> The `log` field already exists on `BundledGithubAccountSource` (it's `private val log = ...Logger.getInstance(...)` from Plan 1). Reuse it.

- [ ] **Step 4: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify existing resolver tests still pass**

The existing test stubs anonymous-implement `IdeGithubAccountSource` with only `override fun listAccounts() = ...`. Now that `findToken` has a default implementation, those stubs continue to compile. Confirm:

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.auth.GitHubAccountResolverTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt
git commit -m "feat(auth): add IdeGithubAccountSource.findToken with bundled-plugin impl"
```

---

## Task 2: `GitHubAccountResolver.resolveAuth(host)` suspend overload

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt`
- Modify: `src/test/kotlin/com/example/ghactions/auth/GitHubAccountResolverTest.kt`

Currently `resolve(host)` returns an `AuthSource` (PAT with token, or IdeAccount with just an id). Callers can use the PAT directly but have to do their own credential lookup for IdeAccount — which is precisely the gap.

`resolveAuth` is a sibling that returns a `ResolvedAuth` data class containing both the `AuthSource` *and* the actual token. It's a `suspend` function because IDE-account credential lookup is async.

- [ ] **Step 1: Add the failing tests**

Append these tests at the end of `src/test/kotlin/com/example/ghactions/auth/GitHubAccountResolverTest.kt`, just before the closing `}` of the class:

```kotlin
    private fun fakePatSource(tokens: Map<String, String>) = object : PatLookup {
        override fun getToken(host: String) = tokens[host]
    }

    private fun fakeIdeSourceWithToken(
        accounts: List<Pair<String, String>>,
        tokenFor: Map<String, String> = emptyMap()
    ) = object : IdeGithubAccountSource {
        override fun listAccounts() = accounts.map { (id, host) -> IdeAccountInfo(id, host) }
        override suspend fun findToken(accountId: String): String? = tokenFor[accountId]
    }

    @Test
    fun `resolveAuth returns null when nothing matches`() = kotlinx.coroutines.test.runTest {
        val r = GitHubAccountResolver(
            ideSource = fakeIdeSource(),
            patLookup = fakePatSource(emptyMap()),
            preferredAccountId = null
        )
        assertNull(r.resolveAuth("https://api.github.com"))
    }

    @Test
    fun `resolveAuth returns Pat with the stored token for a PAT host`() = kotlinx.coroutines.test.runTest {
        val r = GitHubAccountResolver(
            ideSource = fakeIdeSource(),
            patLookup = fakePatSource(mapOf("https://api.github.com" to "ghp_xxx")),
            preferredAccountId = null
        )
        val resolved = r.resolveAuth("https://api.github.com")!!
        assertTrue(resolved.auth is AuthSource.Pat)
        assertEquals("ghp_xxx", resolved.token)
    }

    @Test
    fun `resolveAuth returns IdeAccount with the IDE-fetched token for an IDE account`() =
        kotlinx.coroutines.test.runTest {
            val r = GitHubAccountResolver(
                ideSource = fakeIdeSourceWithToken(
                    accounts = listOf("acct-1" to "https://api.github.com"),
                    tokenFor = mapOf("acct-1" to "ide-token-xyz")
                ),
                patLookup = fakePatSource(mapOf("https://api.github.com" to "fallback-pat")),
                preferredAccountId = null
            )
            val resolved = r.resolveAuth("https://api.github.com")!!
            assertTrue(resolved.auth is AuthSource.IdeAccount)
            assertEquals("acct-1", (resolved.auth as AuthSource.IdeAccount).accountId)
            assertEquals("ide-token-xyz", resolved.token)
        }

    @Test
    fun `resolveAuth falls through to PAT when the matched IDE account has no stored token`() =
        kotlinx.coroutines.test.runTest {
            // IDE account exists but findToken returns null. The resolver should NOT pretend
            // we have IDE auth — it should fall back to PAT if available.
            val r = GitHubAccountResolver(
                ideSource = fakeIdeSourceWithToken(
                    accounts = listOf("acct-1" to "https://api.github.com"),
                    tokenFor = emptyMap() // findToken returns null
                ),
                patLookup = fakePatSource(mapOf("https://api.github.com" to "fallback-pat")),
                preferredAccountId = null
            )
            val resolved = r.resolveAuth("https://api.github.com")!!
            assertTrue(resolved.auth is AuthSource.Pat, "Should have fallen through to PAT; got ${resolved.auth}")
            assertEquals("fallback-pat", resolved.token)
        }
```

> Existing imports cover `assertEquals`, `assertNull`, `assertTrue`. The new tests need `kotlinx.coroutines.test.runTest` (we use the fully-qualified form to avoid touching the imports list — keeps the diff smaller).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.auth.GitHubAccountResolverTest"`
Expected: FAIL with `unresolved reference: resolveAuth` and `ResolvedAuth`. The 5 existing tests still pass.

- [ ] **Step 3: Add `ResolvedAuth` and `resolveAuth` to the resolver**

In `src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt`, add the data class above the `class GitHubAccountResolver` declaration:

```kotlin
/** A successfully-resolved authentication: the source identity and its real token. */
data class ResolvedAuth(val auth: AuthSource, val token: String)
```

Inside the `class GitHubAccountResolver` body, after the existing `resolve` method, add:

```kotlin
    /**
     * Async sibling of [resolve] that also returns the actual token to use. For PAT auth
     * the token is the one already stored in `PasswordSafe`; for IDE-account auth it's
     * fetched via [IdeGithubAccountSource.findToken] (a suspend call into the bundled
     * GitHub plugin's `GHAccountManager`).
     *
     * If the IDE-account lookup fails (no token stored for the matched account), we fall
     * through to the PAT path rather than returning a half-resolved result.
     */
    suspend fun resolveAuth(host: String): ResolvedAuth? {
        val normalizedTarget = normalize(host)

        val matchingIdeAccounts = ideSource.listAccounts()
            .filter { normalize(it.host) == normalizedTarget }

        if (matchingIdeAccounts.isNotEmpty()) {
            val chosen = matchingIdeAccounts.firstOrNull { it.id == preferredAccountId }
                ?: matchingIdeAccounts.first()
            val ideToken = ideSource.findToken(chosen.id)
            if (ideToken != null) {
                return ResolvedAuth(
                    auth = AuthSource.IdeAccount(host = host, accountId = chosen.id),
                    token = ideToken
                )
            }
            // Fall through to PAT — IDE account exists but credential lookup failed.
        }

        patLookup.getToken(host)?.let {
            return ResolvedAuth(auth = AuthSource.Pat(host = host, token = it), token = it)
        }
        return null
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.auth.GitHubAccountResolverTest"`
Expected: PASS, 9 tests (5 original + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt \
        src/test/kotlin/com/example/ghactions/auth/GitHubAccountResolverTest.kt
git commit -m "feat(auth): add suspend GitHubAccountResolver.resolveAuth"
```

---

## Task 3: Extract `ProductionClientFactory` to its own file

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt`
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt` (delete the inline `ProductionClientFactory`)

The inline `internal object ProductionClientFactory` at the bottom of `RunRepository.kt` was small enough to live there in Plan 2. With Plan 5's suspend body and stronger contract it earns its own file. Plan 4's `PullRequestRepository` already references it via fully-qualified name, so the move doesn't break anything.

The signature *stays synchronous* in this task — Task 4 makes it suspend. We extract first so the suspend conversion is a self-contained diff.

- [ ] **Step 1: Read the current `RunRepository.kt`**

Find the existing `internal object ProductionClientFactory { fun create(project: Project): GitHubClient? { ... } }` block at the bottom of the file. Note its full body — you'll move it verbatim.

- [ ] **Step 2: Create the new file with the moved object**

Create `src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt` with the moved content (no behavioural changes yet):

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubClient
import com.example.ghactions.api.GitHubHttp
import com.example.ghactions.auth.AuthSource
import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.GitHubAccountResolver
import com.example.ghactions.auth.PatLookup
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Builds [GitHubClient] instances from the project's bound repo + the user's stored
 * credentials. Used by the project-scoped repositories — kept out of those classes so
 * the credential-resolution logic is testable on its own (and so the repositories don't
 * have to know which auth sources exist).
 *
 * Plan 5 makes this `suspend` so the IDE-account credential lookup can flow through.
 */
internal object ProductionClientFactory {
    private val log = Logger.getInstance(ProductionClientFactory::class.java)

    fun create(project: Project): GitHubClient? {
        val binding = project.getService(com.example.ghactions.repo.RepoBinding::class.java).current ?: return null
        val settings = PluginSettings.getInstance().state

        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        val auth = resolver.resolve(binding.host) ?: return null

        // Plan 2-era stub: only PAT auth produces a usable client; IDE-account auth
        // is wired in Plan 5 (next task).
        val token = when (auth) {
            is AuthSource.Pat -> auth.token
            is AuthSource.IdeAccount -> {
                log.warn("IDE-account credentials not yet wired; user must use a PAT for now.")
                return null
            }
        }
        val patAsAuth = AuthSource.Pat(host = binding.host, token = token)
        val http = GitHubHttp.create(binding.host, patAsAuth)
        return GitHubClient(http)
    }
}
```

> The `Logger` member was inline-anonymous in the old version (`Logger.getInstance(ProductionClientFactory::class.java).warn(...)`). Promoted to a `private val log` here so the next task's additions can reuse it.

- [ ] **Step 3: Delete the inline copy in `RunRepository.kt`**

Open `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`. Delete the entire `internal object ProductionClientFactory { ... }` block at the bottom — from the doc comment above `internal object` through its closing `}`. Leave the `class RunRepository` body alone.

The remaining file ends at the closing `}` of the `RunRepository` class.

- [ ] **Step 4: Verify compile**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL. The `RunRepository.kt`'s secondary constructor still references `ProductionClientFactory.create(project)` via a fully-qualified name — works exactly the same now that it's a sibling file. `PullRequestRepository.kt`'s reference is also unchanged.

- [ ] **Step 5: Run tests to confirm no regression**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.RunRepositoryTest" --tests "com.example.ghactions.repo.RunRepositoryArchiveTest" --tests "com.example.ghactions.repo.PullRequestRepositoryTest"`
Expected: PASS — 16 tests (6 + 5 + 5).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt \
        src/main/kotlin/com/example/ghactions/repo/RunRepository.kt
git commit -m "refactor(repo): extract ProductionClientFactory to its own file"
```

---

## Task 4: Make `ProductionClientFactory.create` suspend and wire IDE-account auth

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt`
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt` (parameter type + secondary constructor)
- Modify: `src/main/kotlin/com/example/ghactions/repo/PullRequestRepository.kt` (parameter type + secondary constructor)

The signature change. After this task an IDE-account selection in settings actually drives API requests with the correct token.

- [ ] **Step 1: Convert `ProductionClientFactory.create` to suspend + use `resolveAuth`**

Replace the entire body of `ProductionClientFactory` with:

```kotlin
internal object ProductionClientFactory {
    private val log = Logger.getInstance(ProductionClientFactory::class.java)

    suspend fun create(project: Project): GitHubClient? {
        val binding = project.getService(com.example.ghactions.repo.RepoBinding::class.java).current ?: return null
        val settings = PluginSettings.getInstance().state

        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        val resolved = resolver.resolveAuth(binding.host) ?: run {
            log.info("No credentials available for ${binding.host}")
            return null
        }
        // Always pass the actual token to GitHubHttp via a Pat-shaped AuthSource — the
        // `Authorization` header is "token <X>" regardless of whether <X> came from a PAT
        // input or an IDE-account credential lookup.
        val patAsAuth = AuthSource.Pat(host = binding.host, token = resolved.token)
        val http = GitHubHttp.create(binding.host, patAsAuth)
        return GitHubClient(http)
    }
}
```

The function is now `suspend` and uses `resolveAuth`. The "IDE-account not wired" warning is gone — that path now produces a real client.

- [ ] **Step 2: Update `RunRepository`'s `clientFactory` parameter type to suspend**

In `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`, find the primary constructor parameter:

```kotlin
private val clientFactory: () -> GitHubClient?,
```

Replace with:

```kotlin
private val clientFactory: suspend () -> GitHubClient?,
```

In the same file, find the secondary `(Project)` constructor:

```kotlin
constructor(project: Project) : this(
    boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
    clientFactory = { ProductionClientFactory.create(project) }
)
```

The lambda body now calls a suspend function — the lambda needs to be a suspend lambda. Kotlin inference should figure this out from the parameter type (`suspend () -> GitHubClient?`), so this line typically works as-is. If the compiler complains "suspend function can only be called from a coroutine or another suspend function", change the lambda to:

```kotlin
    clientFactory = suspend { ProductionClientFactory.create(project) }
```

Otherwise leave it alone.

- [ ] **Step 3: Update `PullRequestRepository`'s `clientFactory` parameter type to suspend**

In `src/main/kotlin/com/example/ghactions/repo/PullRequestRepository.kt`, find:

```kotlin
private val clientFactory: () -> GitHubClient?,
```

Replace with:

```kotlin
private val clientFactory: suspend () -> GitHubClient?,
```

Find the secondary constructor:

```kotlin
constructor(project: Project) : this(
    boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
    clientFactory = { com.example.ghactions.repo.ProductionClientFactory.create(project) }
)
```

Same as Task 4 Step 2 — leave the lambda alone unless the compiler insists, then add `suspend` prefix.

- [ ] **Step 4: Verify compile**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

If compilation fails on the secondary constructors, apply the `suspend { ... }` prefix described in Step 2.

- [ ] **Step 5: Run all repository tests to confirm no behavioural regression**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.*"`
Expected: PASS — 16 tests across `RunRepositoryTest` (6), `RunRepositoryArchiveTest` (5), `PullRequestRepositoryTest` (5). The existing tests pass `clientFactory = { client }` lambdas — Kotlin happily promotes a non-suspend lambda where a `suspend` lambda is expected.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt \
        src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/main/kotlin/com/example/ghactions/repo/PullRequestRepository.kt
git commit -m "feat(auth): wire IDE-account credential lookup end-to-end"
```

---

## Task 5: Settings panel — *Add / manage GitHub accounts…* button

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/settings/GhActionsSettingsPanel.kt`

Add a button to the *Connection* group. Clicking it opens IntelliJ's Settings dialog at the GitHub configurable, where the bundled plugin's existing OAuth flow handles github.com login. After the user closes that dialog, our settings panel doesn't auto-refresh the dropdown — they can close and reopen ours, which is consistent with how IntelliJ usually handles settings cross-references. We surface this in a tooltip on the button.

The configurable's display name is *"GitHub"* under *"Version Control"*; `ShowSettingsUtil.showSettingsDialog(project, displayName)` handles the dispatch. Per JetBrains' docs the display name is what appears in the Settings tree, which matches that header.

- [ ] **Step 1: Read the existing file**

Open `src/main/kotlin/com/example/ghactions/settings/GhActionsSettingsPanel.kt`. The *Connection* group currently has rows for: Base URL, Use IDE-configured GitHub account dropdown, Personal access token, and a *Test connection* row.

- [ ] **Step 2: Add the button to the *Connection* group**

Find the existing *Connection* group. Right after the `row { button("Test connection") { testConnection() }; cell(statusLabel) }` row, add:

```kotlin
            row {
                button("Add / manage GitHub accounts…") { manageAccounts() }
                    .comment(
                        "Opens IntelliJ Settings → Version Control → GitHub. The bundled GitHub " +
                            "plugin handles the github.com OAuth flow there. Reopen this dialog after " +
                            "adding an account to refresh the dropdown above."
                    )
            }
```

- [ ] **Step 3: Add the `manageAccounts()` private method**

Find the existing `private fun testConnection() { ... }` method and add this immediately after its closing brace (still inside the `GhActionsSettingsPanel` class):

```kotlin
    private fun manageAccounts() {
        // Cast workaround: ShowSettingsUtil's `showSettingsDialog(Project, String)` accepts the
        // configurable's display name. "GitHub" is the bundled plugin's display name (verified
        // against IDEA Community 2024.3).
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
            ?: com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "GitHub")
    }
```

> Why pull a project from `ProjectManager` instead of an injected one? The settings panel is application-scoped; it doesn't carry a `Project` reference. `openProjects.firstOrNull()` is the project the user is currently in if any window is open; `defaultProject` is the safe fallback. `ShowSettingsUtil` accepts either.

- [ ] **Step 4: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/settings/GhActionsSettingsPanel.kt
git commit -m "feat(settings): add 'manage GitHub accounts' button jumping to IDE settings"
```

---

## Task 6: Settings panel — auth status indicator

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/settings/GhActionsSettingsPanel.kt`

A static label that reads "Currently active: PAT (`https://api.github.com`)" or "Currently active: IDE account `octocat-id`" or "Currently active: nothing — configure above" depending on what `resolveAuth(state.baseUrl)` would resolve to right now.

The label updates on three triggers: panel-open (initial), after *Test connection* completes, and after Apply (since the user may have changed the base URL or PAT).

- [ ] **Step 1: Add the label declaration**

In `GhActionsSettingsPanel.kt`, find the existing `private val statusLabel = JBLabel(" ")` declaration. Right after it (still in the field-declaration region), add:

```kotlin
    private val authStatusLabel = JBLabel(" ").apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }
```

- [ ] **Step 2: Add a row for it in the *Connection* group**

Below the *Add / manage GitHub accounts…* row added in Task 5, append:

```kotlin
            row("Currently active:") {
                cell(authStatusLabel)
            }
```

- [ ] **Step 3: Add a private method to compute and apply the status**

Find the existing `private fun testConnection() { ... }` method. Just above it (still inside the class), add:

```kotlin
    /**
     * Resolve current credentials for the configured base URL and update [authStatusLabel].
     * Run on a coroutine in [com.intellij.openapi.application.ApplicationManager].
     */
    private fun refreshAuthStatus() {
        val baseUrl = state.baseUrl
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = com.example.ghactions.auth.GitHubAccountResolver(
                ideSource = ideAccountSource,
                patLookup = object : com.example.ghactions.auth.PatLookup {
                    override fun getToken(host: String) = patStorage.getToken(host)
                },
                preferredAccountId = state.preferredAccountId
            )
            val resolved = resolver.resolveAuth(baseUrl)
            val text = when (val auth = resolved?.auth) {
                null -> "nothing — configure above"
                is com.example.ghactions.auth.AuthSource.Pat -> "PAT ($baseUrl)"
                is com.example.ghactions.auth.AuthSource.IdeAccount -> "IDE account ${auth.accountId} ($baseUrl)"
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                authStatusLabel.text = text
            }
        }
    }
```

> `GlobalScope` is normally a code smell, but a settings dialog has no per-session scope to attach to. The launched coroutine self-completes within ~10ms (no I/O — the resolver does in-memory lookups plus a single suspend `findCredentials` call which is also fast). The work doesn't outlive the dialog meaningfully.

- [ ] **Step 4: Call `refreshAuthStatus()` at three trigger points**

a) **Panel construction.** In the existing `init` block (or, if there's no `init`, by appending to the existing `val component: DialogPanel = panel { ... }` decl in a `.apply { … }` — whichever your file structure makes natural), call `refreshAuthStatus()` once at the end. If the class doesn't have an `init` block, add one immediately after the field declarations:

```kotlin
    init {
        refreshAuthStatus()
    }
```

b) **After *Test connection*.** Find the existing `testConnection()` method's body. The connection probe runs on a `Thread` and updates `statusLabel` via `invokeLater`. After the `statusLabel.text = …` assignment, on the same `invokeLater` callback, append a call to `refreshAuthStatus()`:

```kotlin
ApplicationManager.getApplication().invokeLater({
    statusLabel.text = when (result) {
        is TestConnection.Result.Success -> "Connected as @${result.login}"
        is TestConnection.Result.Failure -> "Failed: ${result.message}"
    }
    refreshAuthStatus()
}, modality)
```

c) **After Apply.** The `apply()` method on `GhActionsSettingsPanel` is what IntelliJ calls when the user clicks OK / Apply. At the end of that method (after the existing `pendingToken?.let { … }` block), add:

```kotlin
        refreshAuthStatus()
```

- [ ] **Step 5: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

> If the compiler flags `kotlinx.coroutines.GlobalScope` as needing an opt-in (`@OptIn(DelicateCoroutinesApi::class)`), add that annotation to the `refreshAuthStatus` method:
> ```kotlin
> @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
> private fun refreshAuthStatus() { /* … */ }
> ```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/settings/GhActionsSettingsPanel.kt
git commit -m "feat(settings): show currently-active auth source"
```

---

## Task 7: Manual smoke test (`./gradlew runIde`)

**Files:** none.

Plan 5 has no automated test for the IDE-account end-to-end path because `BasePlatformTestCase` doesn't initialise the bundled GitHub plugin's account state in a way our adapter can read. The smoke test is the verification.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens. (Don't `rm -rf build/idea-sandbox` between iterations — that wipes the test PAT.)

- [ ] **Step 2: Open Settings → Tools → GitHub Actions Monitor**

Verify the new bits:
- A *Currently active:* row at the bottom of the *Connection* group.
- A new *Add / manage GitHub accounts…* button in that group.
- The existing *Use IDE-configured GitHub account* dropdown (from Plan 1).

- [ ] **Step 3: Click *Add / manage GitHub accounts…***

Expected: IntelliJ navigates to *Settings → Version Control → GitHub*. Don't add anything yet — close that dialog (Cancel) and return to *GitHub Actions Monitor*. The *Currently active:* row should still show whatever it showed before.

- [ ] **Step 4: Add a GitHub account via JetBrains' flow**

Go to *Settings → Version Control → GitHub*, click *Add account…*, choose *Log In via GitHub…*. The bundled plugin opens a browser to github.com/login/oauth/authorize. Authenticate. JetBrains stores the token.

- [ ] **Step 5: Reopen our settings panel**

*Settings → Tools → GitHub Actions Monitor*. The IDE-account dropdown should now list the account you just added (its id and host). The *Currently active:* row should still show whatever auth was active before — selecting an account is a separate step.

- [ ] **Step 6: Select the IDE account from the dropdown**

Choose your IDE account in the *Use IDE-configured GitHub account* dropdown. Click *Apply*. The *Currently active:* row should update to *"IDE account &lt;id&gt; (https://api.github.com)"*.

- [ ] **Step 7: Click *Test connection***

Expected: status message reads *"Connected as @&lt;your-github-login&gt;"* — same as the PAT path. Notice the *Currently active:* row stays on the IDE-account line.

- [ ] **Step 8: Open the GitHub Actions tool window and click *Refresh***

Expected: PR tree (or empty state) populates, exactly as before. Confirms the IDE-account credentials are flowing through to the actual API calls.

- [ ] **Step 9: Switch back to PAT auth**

In settings, change *Use IDE-configured GitHub account* back to *(none — use token below)* and click *Apply*. *Currently active:* should flip to *"PAT (https://api.github.com)"*. Click *Refresh* in the tool window — still works (PAT path is untouched by Plan 5).

- [ ] **Step 10: Document any deviations**

If any step fails, write the deviation in `docs/superpowers/notes/2026-04-29-ide-account-auth-smoke-deviations.md` and commit. The most likely failure modes:
- `ShowSettingsUtil.showSettingsDialog(project, "GitHub")` doesn't navigate to the right page on this IDE version. Try the alternate id `"vcs.GitHub"` or `"vcs.git.github"` and update Task 5's `manageAccounts` accordingly.
- `mgr.findCredentials(account)` returns null even though the account was added through the bundled plugin. That points at API drift in the bundled plugin's credential storage; trace `mgr.accountsState.value` and `findCredentials` directly via the IDE's debugger.

```bash
git add docs/superpowers/notes/  # only if a notes file was created
git commit -m "docs: smoke test deviations for plan 5"
```

If no deviations, skip the commit.

---

## Task 8: Final sweep + merge

**Files:** none — verification and merge only.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS. Plan 5 adds 4 new tests (`resolveAuth` cases). Total cumulative tests should be ~103 (99 from Plan 4 + 4).

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Updated zip at `build/distributions/`.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. Acceptable warnings: experimental-API uses (carry-over). The `GHAccountManager.findCredentials` call may surface a new warning since it's marked `@ApiStatus.Internal` in some versions — the warning is acceptable for an internal-distribution plugin; it's the documented integration point.

- [ ] **Step 4: Fast-forward merge to `main`**

```bash
git checkout main
git merge --ff-only feat/plan-5-ide-account-auth
git log --oneline | head -10
```
Expected: clean fast-forward.

- [ ] **Step 5: Plan-level verification**

- All 8 tasks have green check-marks.
- `./gradlew test` passes (~103 tests).
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.
- Manual smoke test (Task 7) all green.
- `git log --oneline` on `main` shows the expected sequence.

---

## What ships at the end of Plan 5

- IDE-account authentication actually works: selecting an IDE-configured GitHub account in our settings drives every API call with that account's token (fetched via `GHAccountManager.findCredentials(...)`).
- A *Add / manage GitHub accounts…* button in our settings opens IntelliJ's GitHub configurable, where the bundled plugin handles the github.com OAuth flow.
- A *Currently active:* row in our settings names the auth source in use (PAT, IDE account, or none).
- The factory split: `ProductionClientFactory` lives in its own file and is properly suspend.

What it does **not** yet do (deferred to later plans):
- **Live polling** — Plan 6.
- **Rate-limit awareness** — Plan 6.
- **Standalone github.com OAuth flow** (independent of the bundled plugin) — Plan 7 if still wanted.
- **ANSI color rendering** — Plan 8.
- **Notifications + status bar widget** — Plan 9+.

---

## Open questions / risks

1. **`GHAccountManager.findCredentials` API drift.** It's `suspend` in 2024.3+ but its signature was different in earlier versions and may be again in 2025.x. The defensive try/catch in `BundledGithubAccountSource` covers crashes; failures degrade to "no IDE token" and the resolver falls through to PAT.
2. **`ShowSettingsUtil` configurable id.** Task 5 uses display name `"GitHub"`. If JetBrains changes the configurable identifier (rare, but happens), the button won't navigate correctly. The smoke-test step explicitly checks this and Task 7 lists alternate ids to try.
3. **GlobalScope in `refreshAuthStatus`.** Acceptable for this use (small in-memory + one suspend call) but a tighter scope would be ideal. Plan 6's polling coordinator can introduce a settings-dialog-tied `CoroutineScope` and we move the call there.
4. **Settings dialog doesn't auto-refresh the dropdown after the user adds an IDE account.** The `manageAccounts()` button opens IntelliJ's settings; when the user navigates back, our existing dropdown still has the stale list. Documented in the button's tooltip; a real fix would re-read accounts on `apply` or on focus-gained.
