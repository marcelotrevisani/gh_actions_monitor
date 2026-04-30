# Plan 16 — Workflow command markers in logs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a workflow step writes a GitHub Actions command marker (`::error file=foo,line=10::message` or `##[error]message`), render it visibly in the log viewer instead of as raw text. Severity-tinted foreground colour + prefix like `[ERROR foo:10] message`.

**Architecture:** A pure `WorkflowCommandParser.parseLine(line): Command?` recognises the two formats GitHub uses. `LogViewerPanel.renderText()` walks each line; matched lines bypass the ANSI parser and produce a single styled `AnsiSpan` whose foreground is fixed (failure/warning/notice palette colour); unmatched lines flow through the existing `AnsiParser.parse` path unchanged.

**Tech Stack:** same as Plans 1–15. No new gradle deps.

**Spec reference:** *Components → log viewer* — workflow command rendering is a v1 nice-to-have called out in brainstorming.

**Sections deliberately deferred:** `::group` / `##[group]` collapsible folding (separate plan), `::add-mask` masking, `::debug`/`::set-output` markers (low value).

**Plans 1–15 carry-overs:** `AnsiParser` / `AnsiPalette` / `AnsiTextPane` / `LogViewerPanel` from Plan 7 · `AnsiColor` enum maps to a fixed palette colour · commits one per task, no `Co-Authored-By`.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/kotlin/com/example/ghactions/ui/ansi/
    │   ├── WorkflowCommand.kt           (new — value types)
    │   └── WorkflowCommandParser.kt     (new — pure parser)
    ├── main/kotlin/com/example/ghactions/ui/
    │   └── LogViewerPanel.kt            (modify — apply marker formatting)
    └── test/kotlin/com/example/ghactions/ui/ansi/
        └── WorkflowCommandParserTest.kt (new)
```

**Cumulative test target:** 165 → 172.

---

## Task 1: `WorkflowCommand` types + `WorkflowCommandParser`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/ansi/WorkflowCommand.kt`
- Create: `src/main/kotlin/com/example/ghactions/ui/ansi/WorkflowCommandParser.kt`
- Create: `src/test/kotlin/com/example/ghactions/ui/ansi/WorkflowCommandParserTest.kt`

GitHub formats:
- `::error::message` and `::error file=foo,line=10,col=5::message` (also `endLine`, `endColumn`)
- `::warning::message`, `::warning file=…::message`
- `::notice::message`, `::notice file=…::message`
- `##[error]message`, `##[warning]message`, `##[notice]message` (older runner format; no file/line)

Anything else returns `null`. The parser handles the *whole line* — leading whitespace before the marker is preserved as-is (we drop it on render but the parser doesn't care).

- [ ] **Step 1: Create the value types**

```kotlin
package com.example.ghactions.ui.ansi

/** Severity of a workflow command marker. Independent of [AnsiColor]. */
enum class CommandLevel { ERROR, WARNING, NOTICE }

/** Parsed marker. [file] and [line] are optional location hints. */
data class WorkflowCommand(
    val level: CommandLevel,
    val message: String,
    val file: String? = null,
    val line: Int? = null
)
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/ui/ansi/WorkflowCommandParserTest.kt`:

```kotlin
package com.example.ghactions.ui.ansi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowCommandParserTest {

    @Test
    fun `plain text returns null`() {
        assertNull(WorkflowCommandParser.parseLine("hello world"))
        assertNull(WorkflowCommandParser.parseLine(""))
        assertNull(WorkflowCommandParser.parseLine("::not-a-real-command::oops"))
    }

    @Test
    fun `colon-colon error without location`() {
        val c = WorkflowCommandParser.parseLine("::error::it broke")
        assertEquals(WorkflowCommand(CommandLevel.ERROR, "it broke"), c)
    }

    @Test
    fun `colon-colon error with file and line`() {
        val c = WorkflowCommandParser.parseLine("::error file=src/main.kt,line=42,col=8::Unresolved reference")
        assertEquals(
            WorkflowCommand(CommandLevel.ERROR, "Unresolved reference", file = "src/main.kt", line = 42),
            c
        )
    }

    @Test
    fun `colon-colon warning without location`() {
        val c = WorkflowCommandParser.parseLine("::warning::deprecated API")
        assertEquals(WorkflowCommand(CommandLevel.WARNING, "deprecated API"), c)
    }

    @Test
    fun `colon-colon notice with extra parameters preserves message`() {
        // Some emitters include endLine, title, etc. We ignore unknown fields and grab `file`/`line`.
        val c = WorkflowCommandParser.parseLine("::notice file=README.md,line=1,endLine=3,title=Heads up::please read")
        assertEquals(
            WorkflowCommand(CommandLevel.NOTICE, "please read", file = "README.md", line = 1),
            c
        )
    }

    @Test
    fun `hashhash format error without location`() {
        val c = WorkflowCommandParser.parseLine("##[error]boom")
        assertEquals(WorkflowCommand(CommandLevel.ERROR, "boom"), c)
    }

    @Test
    fun `leading whitespace is tolerated`() {
        val c = WorkflowCommandParser.parseLine("    ::warning::indented")
        assertEquals(WorkflowCommand(CommandLevel.WARNING, "indented"), c)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

`./gradlew --no-daemon test --tests com.example.ghactions.ui.ansi.WorkflowCommandParserTest` → FAIL.

- [ ] **Step 4: Implement the parser**

Create `src/main/kotlin/com/example/ghactions/ui/ansi/WorkflowCommandParser.kt`:

```kotlin
package com.example.ghactions.ui.ansi

/**
 * Recognises GitHub Actions workflow command markers in a log line. Returns null for
 * lines that don't match one of the supported shapes. The caller decides what to do
 * with the result — this object is pure.
 *
 * **Supported**
 * - `::<level>[ <key>=<value>(,<key>=<value>)*]::<message>`
 *   where `<level>` is `error`, `warning`, or `notice`. Recognised keys: `file`, `line`.
 *   Other keys are ignored.
 * - `##[<level>]<message>` where `<level>` is `error`, `warning`, or `notice`.
 *
 * **Not supported (yet)**
 * - `::group::` / `::endgroup::` / `##[group]` — folding lives in a separate plan.
 * - `::add-mask::`, `::debug::`, `::set-output::` — low-value markers, ignored (return null).
 */
object WorkflowCommandParser {

    private val COLON_RE = Regex(
        """^\s*::(error|warning|notice)(?:\s+([^:]*))?::(.*)$"""
    )
    private val HASH_RE = Regex(
        """^\s*##\[(error|warning|notice)\](.*)$"""
    )

    fun parseLine(line: String): WorkflowCommand? {
        COLON_RE.matchEntire(line)?.let { match ->
            val level = level(match.groupValues[1]) ?: return null
            val params = parseParams(match.groupValues[2])
            return WorkflowCommand(
                level = level,
                message = match.groupValues[3],
                file = params["file"]?.takeIf { it.isNotBlank() },
                line = params["line"]?.toIntOrNull()
            )
        }
        HASH_RE.matchEntire(line)?.let { match ->
            val level = level(match.groupValues[1]) ?: return null
            return WorkflowCommand(level = level, message = match.groupValues[2])
        }
        return null
    }

    private fun level(token: String): CommandLevel? = when (token) {
        "error" -> CommandLevel.ERROR
        "warning" -> CommandLevel.WARNING
        "notice" -> CommandLevel.NOTICE
        else -> null
    }

    private fun parseParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
            .toMap()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

`./gradlew --no-daemon test --tests com.example.ghactions.ui.ansi.WorkflowCommandParserTest` → PASS, 7 tests.

- [ ] **Step 6: Run the full suite**

`./gradlew --no-daemon test` → PASS, 172 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/ansi/WorkflowCommand.kt \
        src/main/kotlin/com/example/ghactions/ui/ansi/WorkflowCommandParser.kt \
        src/test/kotlin/com/example/ghactions/ui/ansi/WorkflowCommandParserTest.kt
git commit -m "feat(ui): add WorkflowCommandParser for ::error/::warning/::notice markers"
```

---

## Task 2: Wire marker formatting into `LogViewerPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt`

The panel currently feeds the visible text into `AnsiParser.parse(text)`. We add a pre-pass that walks line by line, replaces marker lines with formatted+colored equivalents, and joins the rest unchanged for the ANSI parser.

The AnsiParser already returns `List<AnsiSpan>`. We can produce a *formatted* span per matched marker line and stitch them with the parser output for unmatched lines. Simplest approach: replace marker lines in the input with **plain rendered text** (e.g. `[ERROR src/main.kt:10] message`) wrapped in ANSI escape codes for the right colour, then run the whole thing through `AnsiParser.parse`. That keeps `LogViewerPanel` simple — only the textual transform changes; the rendering pipeline is untouched.

ANSI codes for the palette:
- ERROR: `[31m...` (red, code 31 → `AnsiColor.RED`)
- WARNING: `[33m...` (yellow, code 33 → `AnsiColor.YELLOW`)
- NOTICE: `[36m...` (cyan, code 36 → `AnsiColor.CYAN`)

The `[0m` reset trail at the end of each marker line keeps the colour from bleeding into subsequent unmarked lines.

- [ ] **Step 1: Add the transform**

In `LogViewerPanel.kt`, find `private fun renderText()`. It currently does:

```kotlin
val visible = if (timestampToggle.isSelected) {
    rawText
} else {
    rawText.lineSequence().joinToString("\n") { stripTimestamp(it) }
}
textPane.setSpans(AnsiParser.parse(visible))
```

Replace with a pre-pass that swaps marker lines:

```kotlin
val withoutTimestamps = if (timestampToggle.isSelected) {
    rawText
} else {
    rawText.lineSequence().joinToString("\n") { stripTimestamp(it) }
}
val withMarkers = withoutTimestamps.lineSequence().joinToString("\n") { line ->
    val cmd = com.example.ghactions.ui.ansi.WorkflowCommandParser.parseLine(line)
        ?: return@joinToString line
    formatMarkerLine(cmd)
}
textPane.setSpans(AnsiParser.parse(withMarkers))
```

Add the private helper to the same class:

```kotlin
private fun formatMarkerLine(cmd: com.example.ghactions.ui.ansi.WorkflowCommand): String {
    val tag = when (cmd.level) {
        com.example.ghactions.ui.ansi.CommandLevel.ERROR -> "ERROR"
        com.example.ghactions.ui.ansi.CommandLevel.WARNING -> "WARNING"
        com.example.ghactions.ui.ansi.CommandLevel.NOTICE -> "NOTICE"
    }
    val ansiOpen = when (cmd.level) {
        com.example.ghactions.ui.ansi.CommandLevel.ERROR -> "[1;31m"   // bold red
        com.example.ghactions.ui.ansi.CommandLevel.WARNING -> "[1;33m" // bold yellow
        com.example.ghactions.ui.ansi.CommandLevel.NOTICE -> "[1;36m"  // bold cyan
    }
    val ansiClose = "[0m"
    val location = listOfNotNull(cmd.file, cmd.line?.toString()).joinToString(":")
    val prefix = if (location.isEmpty()) "[$tag]" else "[$tag $location]"
    return "$ansiOpen$prefix $ansiClose${cmd.message}"
}
```

The colour applies to the `[TAG file:line]` prefix only; the message itself stays default-coloured so it's still readable on dim themes.

- [ ] **Step 2: Verify compile**

`./gradlew --no-daemon compileKotlin` → SUCCESS.

- [ ] **Step 3: Run the full suite**

`./gradlew --no-daemon test` → PASS, 172 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt
git commit -m "feat(ui): render workflow command markers with severity prefix and colour"
```

---

## Task 3: Smoke + final sweep + merge

- [ ] **Step 1: Manual smoke (deferred OK)**

Run `./gradlew --no-daemon runIde`. Pick a step with workflow commands (test runners and linters commonly emit them):
- `::error` lines should appear as bold red `[ERROR file:line] message` instead of the raw `::error file=…::msg`.
- `::warning` → bold yellow.
- `::notice` → bold cyan.
- The `##[error]` shorthand emits the same prefix, no file/line.
- Unmarked lines render unchanged.
- Toggle *Show timestamps* — markers still detected after timestamp stripping.
- Lines that look like markers but use unsupported levels (`::debug::…`) pass through as plain text (parser returns null).

- [ ] **Step 2: Full test run** — `./gradlew --no-daemon cleanTest test` → 172 tests.
- [ ] **Step 3: Build distribution** — `./gradlew --no-daemon buildPlugin` → SUCCESS.
- [ ] **Step 4: Plugin verifier** — `./gradlew --no-daemon verifyPlugin` → SUCCESS (existing 1 SegmentedButton warning acceptable).
- [ ] **Step 5: Fast-forward merge to main**

```bash
git checkout main
git merge --ff-only feat/plan-16-workflow-command-markers
```

- [ ] **Step 6: Plan-level verification** — all 3 tasks ✅.

---

## What ships at the end of Plan 16

- Workflow command markers (`::error`, `::warning`, `::notice` and their `##[…]` shorthand) render as a colour-coded prefix in the log viewer.
- File/line annotations (when present) are inlined: `[ERROR src/main.kt:10] message`.
- The raw command syntax no longer appears in the visible log.
- Tooltips and copy-paste behave the same as the rest of the log; no special handling needed.

What it does **not** yet do:
- `::group` / `##[group]` folding (separate plan).
- `::add-mask` (would let us blank out matched substrings).
- `::debug` (passes through as plain text).

---

## Open questions / risks

1. **Multiple commands per line.** Rare; if it ever happens we render only the first match. Expanding this is a future polish — would need a multi-span output rather than the single-line replacement we do now.
2. **Marker inside an ANSI-colored span.** Our pre-pass strips the marker syntax and re-inserts our own ANSI colour; any pre-existing ANSI inside the marker's `message` body is preserved (the AnsiParser handles it later).
3. **Line endings.** The pre-pass uses `lineSequence()` which respects both `\n` and `\r\n` correctly; `joinToString("\n")` collapses to LF — matches the existing behaviour.
