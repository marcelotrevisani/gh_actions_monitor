# PyCharm GitHub Actions Monitor — Design

**Date:** 2026-04-28
**Status:** Draft (pre-implementation)

## Goal

A JetBrains IDE plugin (PyCharm Community/Professional, IntelliJ IDEA, and other IDEs that bundle the Git/GitHub plugins) that lets a developer monitor and act on GitHub Actions for the repo of the currently-open project — without leaving the IDE.

The plugin is intentionally bound to the *current project's* GitHub repo, leveraging the IDE context the user already has. It is not a general-purpose GitHub Actions client.

### Definitions

- **Bound repo** — the GitHub repo resolved from the open project's `origin` git remote (the `RepoBinding`).
- **Current PR** — the PR on the bound repo whose head ref matches the project's currently checked-out branch (HEAD). At most one. Used by the status bar widget and notifications.
- **User's open PRs** — all open PRs on the bound repo authored by the authenticated user. Shown in the PR-centric view's left list. Plural, can be many.
- **Tracked runs** — the polling-priority set: runs belonging to any of the user's open PRs, plus runs the user has explicitly clicked "Watch" on. Used by the *Tracked* polling tier and notifications.

## Scope (v1)

All of the following ship in v1:

- Authentication via three sources, in priority order:
  1. IDE-configured GitHub accounts (bundled `org.jetbrains.plugins.github` plugin).
  2. Personal Access Token (classic or fine-grained), stored in IntelliJ's `PasswordSafe`.
  3. Empty state with link to settings if neither is configured.
- Both **github.com** and **GitHub Enterprise Server** (configurable base URL).
- Tool window with **three selectable view modes**, default *PR-centric*:
  - **PR-centric** (default): list of the user's open PRs on the bound repo, with check-run status; selecting a PR shows its runs.
  - **Tabbed master/detail**: tabs *My PRs · All Runs · Workflows · Queue*, each list-on-left/detail-on-right.
  - **Single tree**: unified hierarchical tree of PRs / Workflows / Recent runs.
- Run detail with four sub-tabs: **Logs · Annotations · Summary · Artifacts**.
- **Live logs** for in-progress jobs via adaptive polling (3s foreground / 15s tracked / 30s visible-list / 60s idle), with focus-pause, an enable/disable toggle, and a manual refresh button.
- **Filters**: PR, Workflow (multi-select), Status, Conclusion, Actor, Branch, Event, Time range. State persisted per tab/view.
- **Annotations** integration with three layers: list view in detail panel + inline gutter markers in the editor + entries in the Problems tool window. Stale annotations (run's `head_sha` ≠ file's current state) are suppressed by default with a toggle to show them.
- **Step summaries** rendered as full-fidelity HTML in an embedded JCEF browser, with a "view raw" fallback.
- **Artifacts**: list with size/expiry, download to user-chosen location (default remembered per project), and IDE-preview for small text artifacts.
- **Run workflow** (`workflow_dispatch`) with a dynamic inputs dialog parsed from the workflow YAML.
- **Re-run all jobs** and **Re-run failed jobs only** as separate actions.
- **Notifications**: balloon for tracked items (default *failures only*; configurable *off / failures only / all completions*) + a status bar widget showing aggregate CI status for the current PR.
- **Rate-limit awareness**: graceful slowdown below 20% budget, hard pause + resume on 403/429, status bar surfacing.

### Out of scope for v1

- Multi-repo or org-wide monitoring (current-project binding only).
- Webhook-based event delivery.
- Saved "run profiles" with named input sets (deferred until users ask).
- Inline check decorations on commits in the IDE's git log.
- "Run on Actions" gutter actions (mismatch with how dispatch works).
- Workflow YAML completion (handled by other plugins / schemas).

## Architecture

### Module & package layout

Single-module Gradle project using the `org.jetbrains.intellij.platform` Gradle plugin. Packages under `com.example.ghactions`:

| Package | Responsibility |
|---|---|
| `api/` | GitHub HTTP client, DTOs, rate-limit handling. No IDE dependencies. |
| `auth/` | Account resolution (IDE accounts → PAT in `PasswordSafe`), settings UI. |
| `domain/` | Plain Kotlin data classes (Run, Job, Step, Annotation, Artifact, Workflow) and DTO mappers. |
| `repo/` | Project-scoped state cache (single source of truth) exposing `StateFlow`s. |
| `polling/` | Adaptive polling coordinator. |
| `ui/` | Tool window factory, three view modes, shared sub-panels. |
| `ide/` | IDE-side integrations: gutter markers, Problems contribution, status bar widget, balloon notifications, action handlers. |
| `events/` | `MessageBus` topic definitions for cross-cutting signals. |

### Lifetimes

- **Project-scoped** services (`GitHubActionsService`, `RunRepository`, `PollingCoordinator`, `RepoBinding`): live as long as the project is open; disposed automatically with it.
- **Application-scoped**: settings, the underlying HTTP client.

### Threading

- All HTTP and cache work on Kotlin coroutine dispatchers (`Dispatchers.IO`).
- UI updates marshalled to EDT via `Dispatchers.Main` (which the platform binds to the EDT).
- No raw `Thread` or `SwingWorker`.

### Data flow pattern (Approach 3 — hybrid)

- **Layered MVVM with coroutines/Flows** for run/job/log state. UI observes `StateFlow`s; ViewModels expose derived (filtered/sorted) flows; the `RunRepository` is the single source of truth.
- **`MessageBus`** reserved for cross-cutting signals only: `AuthChanged`, `RepoBindingChanged`, `RateLimitChanged`. Anything else flows through `RunRepository`.

## Components

### `api/` — GitHub client

- **`GitHubClient`** — single HTTP entry point per `(baseUrl, credentials)` tuple. Built on **Ktor client**. Methods cover: list runs (per PR, per repo, per workflow), get run, list jobs, get job logs, list annotations, list artifacts, download artifact, dispatch workflow, re-run all, re-run failed, list workflows, get workflow YAML.
- **`RateLimitInterceptor`** — reads `X-RateLimit-Remaining` / `-Reset` from every response, publishes `RateLimitChanged`. Throws typed `RateLimitedException` on 403/429 with `Remaining: 0`.
- **`LogStreamReader`** — wraps `getJobLogs` with delta tracking. Tries HTTP `Range: bytes=<lastSeen>-`; falls back to full-fetch + local diff when ranges unsupported.

### `auth/`

- **`GitHubAccountResolver`** — discovers credentials in priority order: matching IDE-configured GitHub account → plugin-stored PAT in `PasswordSafe`. Returns an `AuthSource`. The IDE account always wins when both exist for the same host.
- **`PluginSettings`** (`PersistentStateComponent`) — non-secret config: account email reference, base URL override, polling enabled, view mode, notification level, default download dir, last-used filter values. **Never** stores secrets.

### `domain/`

Plain Kotlin data classes mirroring the slice of GitHub's models we use. Mappers (`RunDto.toDomain()`) live next to DTOs.

### `repo/`

- **`RunRepository`** — project service. Holds `MutableStateFlow<Map<RunId, Run>>`, per-run `MutableStateFlow<JobsState>`, per-job `MutableStateFlow<LogState>`. All mutations funnel through here. Provides observation flows and `requestRefresh(...)` entry points.
- **`RepoBinding`** — resolves the current project's GitHub repo from its git remotes. Listens to VCS events to invalidate. Publishes `RepoBindingChanged`.

### `polling/`

- **`PollingCoordinator`** — owns one coroutine scope. Each tick, computes a polling plan from: foreground job, tracked PR set, visible list, IDE focus, rate-limit budget, user toggle. Triggers `RunRepository` updates. Recovers from `RateLimitedException` by pausing all tiers and scheduling a resume tick at `reset + 5s` jitter.

### `ui/`

- **`GhActionsToolWindowFactory`** — registers the tool window (id `GitHubActions`, default anchor *right*). Instantiates the root panel based on `PluginSettings.viewMode`.
- **View-mode roots**: `PrCentricPanel`, `TabbedMasterDetailPanel`, `TreePanel`. All composed from shared sub-panels.
- **Shared sub-panels**:
  - `RunListPanel` — virtualized `JBList`/`JBTable`. Row: status icon · workflow · branch · actor · age · duration.
  - `FilterBar` — drop-down chips for each filter; persisted per tab.
  - `RunDetailPanel` — top: jobs/steps tree; bottom tabs: Logs · Annotations · Summary · Artifacts.
  - `LogViewerPanel` — `EditorEx` (read-only), ANSI color, `::group::`/`::endgroup::` folding, auto-scroll-tail.
  - `AnnotationsPanel` — table; double-click → `OpenFileDescriptor`.
  - `SummaryPanel` — JCEF browser; falls back to plain-text on platforms without JCEF.
  - `ArtifactsPanel` — table with Preview / Download.
- **`RunWorkflowDialog`** — branch picker + dynamic input form parsed from workflow YAML.

### `ide/`

- **`AnnotationHighlighter`** — project service maintaining `(filePath → List<Annotation>)` for the *currently selected run* (not all runs).
- **`AnnotationLineMarkerProvider`** — contributes `LineMarkerInfo`s; suppresses when `head_sha` ≠ file's current VCS state.
- **`AnnotationProblemsProvider`** — contributes annotations to the Problems tool window under a dedicated group node.
- **`PrCiStatusBarWidget`** — `StatusBarWidget.IconPresentation`. Aggregate CI status for the active branch's PR. Click → focus tool window. Right-click → context menu.
- **`Notifier`** — wraps `NotificationGroupManager`. Suppresses duplicates within 60s. Honors `notificationLevel` setting. Fires only for *tracked* items.
- Action classes: `RunWorkflowAction`, `RerunAllAction`, `RerunFailedAction`, `CancelRunAction`, `OpenOnGitHubAction`, `RefreshAction`, `ToggleLivePollingAction`, `ChangeViewModeAction`, `JumpToRunAction`.

### `events/`

Three `Topic`s only: `AuthChanged`, `RepoBindingChanged`, `RateLimitChanged`.

## Representative data flows

### A. Opening the tool window

1. Factory reads `PluginSettings.viewMode` → instantiates root panel.
2. Root panel observes `RepoBinding.current`. If null → empty state. If resolved → `RunRepository.requestPrRuns(...)`.
3. Cold cache → `PollingCoordinator.scheduleImmediate(prRunsTask)`.
4. HTTP call on `Dispatchers.IO` → DTO → domain → state-flow update.
5. UI collects the flow on `Dispatchers.Main` → repaints.

### B. Live logs for a running job

1. User clicks job → `RunDetailPanel.show(jobId)` → `PollingCoordinator.setForeground(jobId)`.
2. Foreground tier polls every 3s: `LogStreamReader.fetchDelta(jobId)`.
3. New chunk → `RunRepository.logsFlow(jobId)` → `LogViewerPanel`'s `EditorEx` Document.
4. On status flip to `completed`: one final full fetch → drop foreground tier.
5. On `RateLimitedException`: pause all tiers, banner via `Notifier`, `RateLimitChanged` updates status bar.

### C. Run workflow

1. Action opens `RunWorkflowDialog`. Dialog calls `getWorkflowYaml`, parses `on.workflow_dispatch.inputs`, builds typed form fields.
2. Submit → `dispatchWorkflow(workflowId, ref=branch, inputs)`.
3. Since the API doesn't return the new run id, `PollingCoordinator.requestImmediate(listRunsForRepo)` with a tight follow-up tick — new run surfaces in the list within seconds.
4. `Notifier` shows "Run started" balloon with click-to-jump.

### Cross-cutting: auth change

Settings save → `AuthChanged` published. Subscribers: `GitHubClient` (rebuild auth headers), `RunRepository` (invalidate all), UI (transient "Reconnecting…" state).

## Authentication & settings

### Account resolution order

1. IDE-configured GitHub account whose host matches the bound repo.
2. Plugin-stored PAT in `PasswordSafe`, scoped per host (`service="GhActionsPlugin"`, `userName=<host>`).
3. Empty state.

### Settings panel

Mounted at *Settings → Tools → GitHub Actions Monitor*.

| Field | Type | Notes |
|---|---|---|
| Use IDE-configured GitHub account | dropdown | Includes "(none — use token below)" |
| Base URL | text | Default `https://api.github.com`; required for GHES |
| Personal access token | password | `PasswordSafe` only; never in `state.xml` |
| Test connection | button | `GET /user` |
| Live polling enabled | toggle | Default on |
| Notification level | radio: off / failures only / all completions | Default *failures only* |
| Default download directory | path | Per project |
| Default view mode | radio: PR-centric / Tabbed / Tree | Quick-toggle also in tool window header |

### Required token scopes

- **Classic PAT:** `repo` (private) or `public_repo` + `workflow`.
- **Fine-grained PAT:** scoped repo access; *Actions: read & write*, *Contents: read*, *Metadata: read*, *Pull requests: read*.

### Secrets non-negotiables

- Tokens only in `PasswordSafe`. Never in `state.xml`, never in logs, never in error reports.
- `GitHubClient` masks `Authorization` headers in any debug logging.
- `state.xml` may store: base URL, account email reference, view mode, polling toggle, notification level, last-used download dir, last-used filter values per tab.

## Polling & rate-limit strategy

### Tiers

| Tier | Membership | Cadence | Notes |
|---|---|---|---|
| Foreground | Single job whose logs are visible | 3s | Logs + status |
| Tracked | Runs on user's open PRs; explicitly watched runs | 15s | Run-level only |
| Visible-list | Runs visible in the active tab's list | 30s | Run-level only |
| Idle | Background sweep of in-progress runs | 60s | Skipped if covered above |

A tier is **fully suspended** when: IDE loses focus (`ApplicationActivationListener`), tool window hidden, or polling toggled off.

Manual refresh bypasses tier-cadence logic and runs immediately. Under a *hard* rate-limit pause, it is also blocked — the user must explicitly choose *Refresh anyway* on the rate-limit banner to issue a single forced request.

### Rate-limit handling

- **Soft** — below 20% budget: foreground stays at 3s; tracked → 30s; visible-list → 60s; idle suspended. Status bar tooltip surfaces remaining count.
- **Hard** — 403 with `Remaining: 0` or 429: pause everything; schedule resume at `reset + 5s` jitter; banner via `Notifier`. *"Refresh anyway"* action does a single forced request.
- **Conditional requests**: `If-None-Match` with stored `ETag`s on `getRun` and `listRunsForPr`. `304` is free.

### Cache eviction (in `RunRepository`)

- Completed runs older than 24h: evicted on next idle tick.
- Logs for non-foreground completed jobs after 5 minutes: evicted (re-fetched on demand).
- Everything for a repo that's no longer the active `RepoBinding`: evicted on `RepoBindingChanged`.

## UI: tool window structure

### Tool window header

View-mode switcher · live-polling toggle · manual refresh · settings shortcut · rate-limit indicator.

### View mode C — PR-centric (default)

PR list on the left (with status badges per PR), filter bar above; selecting a PR populates the right-hand detail panel with its runs and jobs/steps tree, then the four sub-tabs (Logs / Annotations / Summary / Artifacts).

### View mode A — tabbed master/detail

Tabs across top: *My PRs · All Runs · Workflows · Queue*. Each is list-on-left/detail-on-right reusing the shared sub-panels. *Workflows* tab uses workflow definitions (with last-run status + Run button per workflow). *Queue* = filter `status=queued` ordered by enqueued-at.

### View mode B — single tree

Hierarchical tree: *Open PRs / Workflows / Recent runs*. Detail panel renders for run/job/step nodes.

### Empty / error states

- No GitHub remote → empty state with link to settings.
- No credentials → "Add GitHub credentials in Settings" deeplink.
- API error (non-rate-limit) → red banner above list with retry.
- Rate limited → yellow banner.

### Persistence

- View mode (per project), filter values per tab/view, tool window dimensions and split positions.
- Selection across IDE restarts: not persisted.

## IDE integrations

### Annotation gutter markers

- `AnnotationLineMarkerProvider` registered via `com.intellij.codeInsight.lineMarkerProvider`.
- Renders only for files matching the *currently selected run*'s `head_sha`. SHA mismatch → markers suppressed; annotations panel header surfaces *"Annotations are from commit `abc1234` — your working copy differs. Show stale markers anyway?"* with a toggle.
- Returns immediately for files with no annotations (cheap path).

### Problems tool window

- Uses `com.intellij.problems.ProblemsProvider`.
- Annotations grouped under *"GitHub Actions: Run #N (workflow · job)"*.
- Same SHA-mismatch guard.
- Click navigates to file/line *and* opens the corresponding job in the tool window.

### Status bar widget

- `StatusBarWidget.IconPresentation`.
- Aggregate CI state for the active branch's PR: ✓ / ✗ / ◐ / ◯ / ⊘. Numeric overlay = in-progress count.
- Click → focus tool window with that PR.
- Right-click → *Refresh now · Disable polling · Plugin settings*.
- Subscribes to `RateLimitChanged`: dims and surfaces "Live updates paused".

### Notifications

- Single `NotificationGroup` `id="GitHubActions.Notifications"`, displayType `BALLOON`.
- Fires only for *tracked* runs and only when `notificationLevel` permits.
- Suppresses duplicates for the same `runId` within 60s.
- Actions on each balloon: *View run · Re-run failed*.

### Action contributions (`plugin.xml`)

- **Tool window toolbar**: `RefreshAction`, `ToggleLivePollingAction`, `ChangeViewModeAction`.
- **Tool window context menu** (run/workflow rows): `RerunAllAction`, `RerunFailedAction`, `CancelRunAction`, `OpenOnGitHubAction`, `RunWorkflowAction`.
- **Editor gutter context menu** (over annotation icons): `JumpToRunAction`, `DismissAnnotationAction` (session-scoped).
- **VCS log context menu**: *"View Actions runs for this commit"*.

### Deliberately out of scope

- "Run on Actions" gutter actions.
- Workflow YAML completion.
- Inline check decorations on commits in the git log.

## Error handling

### Categories

| Category | Examples | Handling |
|---|---|---|
| Authentication | 401, expired token | Pause polling; banner with *Re-authenticate* link. No retry. |
| Authorization | 403 (non-rate-limit), 404 on a known repo | Banner: scope/access guidance. Polling continues for unaffected resources. |
| Rate limit | 403/429 with `Remaining: 0` | See polling section. |
| Network / transient | Connection reset, 5xx, timeout | Exponential backoff: 1s, 3s, 9s. Max 3 attempts. Then banner + retry button. |
| Logic / parsing | Unexpected JSON, malformed workflow YAML | Logged at `warn`; partial UI degradation (e.g., dispatch dialog falls back to "branch only"). |

### Surfacing principle

Errors are surfaced where the user is already looking. Cross-cutting failures (auth, rate-limit) → top-of-tool-window banner. Localized failures → in the panel that requested the data.

### Logging

- `Logger.getInstance("GhActions")` with sub-loggers per module.
- Default `INFO`, `DEBUG` via standard IDE config.
- **Never logged**: tokens, full `Authorization` headers, PAT prefixes longer than the first 4 chars.

### Crash containment

- Coroutine top-level handlers (`CoroutineExceptionHandler`) catch + log + recovery tick.
- Swing event handlers wrap their body in `try/catch` → panel-local error banner.
- Tool window factory has a fallback: any panel construction throwing → *"Something went wrong starting the GitHub Actions panel"* with *Copy diagnostics* button (IDE version, plugin version, last 100 sanitized log lines).

### Cancellation

- Manual refresh while a refresh is in flight: cancel the in-flight one.
- Switching selected run while logs fetching: cancel previous job's log fetch.
- Project close: polling scope cancelled via `Disposer`.

## Testing strategy

### Layers

1. **Pure unit tests** — JUnit 5, no IDE. Covers domain mappers, filter logic, polling-plan computation, log-delta, ANSI parser, group-folding detector, `RateLimitState`.
2. **Service-level integration** — **MockWebServer**. Exercises `GitHubClient` request/response shapes; drives `PollingCoordinator` end-to-end through simulated rate-limit transitions. Recorded GitHub responses (secret-scrubbed) under `src/test/resources/github-fixtures/`.
3. **IntelliJ Platform tests** — `BasePlatformTestCase`. Tool window factory, `AnnotationHighlighter` SHA-match behavior, settings round-trip, status bar widget state, fixture-based smoke tests.

### Not tested automatically

- JCEF rendering (smoke-tested manually).
- Real github.com calls (manual pre-release).
- Pixel-level Swing layouts (we test behavior, not pixels).

### Manual test checklist (`docs/manual-test-checklist.md`)

- IDE GitHub account → tool window populates without entering a token.
- Project with no GitHub remote → empty state.
- Workflow_dispatch with required inputs → dialog renders, run starts, appears within 10s.
- Re-run failed → status updates correctly.
- Annotations matching HEAD → gutter markers; modify file → markers suppressed with stale-warning.
- Force rate-limit (low-budget PAT) → banner; resumes after reset.
- Switch view modes while polling active → no leaks (heap dump check).

### Coverage

- ~80% line coverage target on `api/`, `domain/`, `repo/`, `polling/`. Smoke signal, not a gate.
- Platform tests not measured by line coverage.

### CI (`.github/workflows/ci.yml`)

- Build + unit + service tests on every push (Linux, JDK 17).
- Platform tests on push to `main` and on PR.
- `verifyPlugin` (IntelliJ Plugin Verifier) against the supported IDE range.
- On tag push: build distribution, attach to GitHub Release. Marketplace upload manual until plugin is mature.

## Build & distribution

- Gradle, Kotlin DSL, `org.jetbrains.intellij.platform` Gradle plugin.
- JDK 17 (matches current IntelliJ Platform requirement).
- Targets: PyCharm Community, PyCharm Professional, IntelliJ IDEA Community/Ultimate, GoLand, WebStorm — anywhere the bundled `org.jetbrains.plugins.github` and Git plugins run. `plugin.xml` declares dependencies on those bundled plugins.
- `since-build` / `until-build` set conservatively; widened as `verifyPlugin` confirms compatibility.

## Open items deferred to v2 (informational)

- Multi-repo monitoring (manually pinned repos).
- Saved run profiles for `workflow_dispatch`.
- Org-wide views.
- Inline check decorations on git-log commits.
- Auto-extract artifacts; richer artifact previews (binary, structured formats).
