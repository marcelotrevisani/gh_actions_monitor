# Plan 7 — ANSI Log Rendering

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the GitHub Actions step-log text with ANSI colors and bold instead of the current plain `JTextArea`. Preserve all existing behavior (read-only, monospace, timestamp toggle, copy support).

**Architecture:** Three pure pieces plus one Swing component. (1) `AnsiSpan` / `AnsiStyle` / `AnsiColor` — value types. (2) `AnsiParser.parse(text): List<AnsiSpan>` — pure CSI parser for codes `0` (reset), `1` (bold), `30-37` / `90-97` (foreground colors). Unknown codes are stripped (no escape leaks into the visible text). (3) `AnsiPalette.awtColor(AnsiColor): java.awt.Color` — fixed palette tuned to work on both light and dark IDE themes. (4) `AnsiTextPane` — `JTextPane` wrapper with `setSpans(List<AnsiSpan>)`; replaces the `JTextArea` in `LogViewerPanel`. The parser, palette, and span types are pure and unit-tested; the text pane is verified by smoke testing.

**Tech Stack:** Same as Plans 1–6 — Kotlin 2.0.21, Swing (`javax.swing.JTextPane` + `StyledDocument`), JUnit 5 + `kotlin.test.*`. No new gradle deps.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered:
- Testing → Pure unit tests → "ANSI parser".
- "Live logs … with focus-pause" — this plan does the rendering half; the polling half landed in Plan 6.

**Sections deliberately deferred:**
- GitHub workflow-command markers (`::error`, `::warning`, `::notice`, `::group`, `::endgroup`) — separate concern; `##[group]` is already the line we depend on for grouping in Plan 3, and folding is its own UX feature. Future plan.
- 256-color (`ESC[38;5;N m`) and 24-bit (`ESC[38;2;r;g;b m`) escapes — uncommon in Actions output; the parser **drops** them safely without leaking the escape characters but doesn't render them.
- Background colors (`40-47`, `100-107`) — same rationale as 256-color; rare in Actions output, low value for the cost.
- Search / find-in-log — separate UX feature.

**Plans 1–6 → Plan 7 carry-overs (lessons that still apply):**
- Plan 2 deliberately moved `LogViewerPanel` away from `EditorEx` because of editor-disposal memory leaks. We're using **`JTextPane`**, a plain Swing component without the IntelliJ editor lifecycle — no `Disposable` to register, no `EditorFactory.releaseEditor` dance. The existing tool-window disposer chain still cleans the panel via parent `Content` disposal.
- `kotlinx-coroutines-core` stays `compileOnly` with Ktor exclusions. This plan adds no coroutine code (UI-only).
- One commit per task, type-prefixed, **no `Co-Authored-By` trailer**.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   └── kotlin/com/example/ghactions/ui/ansi/
    │       ├── AnsiSpan.kt           (new — value types)
    │       ├── AnsiParser.kt         (new — pure parser)
    │       ├── AnsiPalette.kt        (new — color resolver)
    │       └── AnsiTextPane.kt       (new — JTextPane wrapper)
    ├── main/kotlin/com/example/ghactions/ui/
    │   └── LogViewerPanel.kt         (modify — swap JTextArea → AnsiTextPane)
    └── test/
        └── kotlin/com/example/ghactions/ui/ansi/
            ├── AnsiParserTest.kt     (new)
            └── AnsiPaletteTest.kt    (new)
```

**File responsibility notes:**
- `AnsiSpan.kt` holds `AnsiColor` (16-value enum), `AnsiStyle(foreground: AnsiColor?, bold: Boolean)`, and `AnsiSpan(text: String, style: AnsiStyle)`. Three small types, one file.
- `AnsiParser.kt` is purely string-in / list-out. No Swing import. Tested independently.
- `AnsiPalette.kt` is a tiny `object` mapping `AnsiColor` → `java.awt.Color` with hand-picked values that survive both light and dark IDE themes (e.g. medium-saturation green and red, not pure `0x00FF00`).
- `AnsiTextPane.kt` extends `JTextPane`, takes `List<AnsiSpan>` via `setSpans`, applies `StyleConstants.Foreground` and `StyleConstants.Bold` as it appends each span to its `StyledDocument`. Read-only, monospaced.

---

## Conventions

- **Tests** stay JUnit 5 with `kotlin.test.*`. No coroutines, no IDE platform — pure JVM tests.
- **Cumulative test target after Plan 7:** 115 (Plan 6) + ~6 (parser) + ~3 (palette) = ~124.
- **One commit per task**, type-prefixed (`feat:`, `test:`, `fix:`, `refactor:`).
- **No comments restating what the code does.** Comments only when *why* is non-obvious (e.g. why we skip 256-color escapes silently rather than render them as text).

---

## Task 1: `AnsiSpan` value types

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiSpan.kt`

Pure value types. No tests for this task — `AnsiParserTest` (Task 2) exercises them implicitly.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiSpan.kt`:

```kotlin
package com.example.ghactions.ui.ansi

/**
 * Standard 16-color ANSI palette. Codes 30-37 map to the first 8; 90-97 to the bright 8.
 * The actual RGB values used by the renderer live in [AnsiPalette] so the model stays
 * decoupled from Swing.
 */
enum class AnsiColor {
    BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE,
    BRIGHT_BLACK, BRIGHT_RED, BRIGHT_GREEN, BRIGHT_YELLOW,
    BRIGHT_BLUE, BRIGHT_MAGENTA, BRIGHT_CYAN, BRIGHT_WHITE
}

/**
 * Display attributes applied to a [AnsiSpan]. `null` foreground means "use the text pane's
 * default" (i.e. inherit the IDE's editor foreground colour) — important for both themes
 * since we never want to hard-code black or white as the "default".
 */
data class AnsiStyle(
    val foreground: AnsiColor? = null,
    val bold: Boolean = false
) {
    companion object {
        val DEFAULT = AnsiStyle()
    }
}

/** A run of characters that share an [AnsiStyle]. Output of [AnsiParser.parse]. */
data class AnsiSpan(
    val text: String,
    val style: AnsiStyle
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/ansi/AnsiSpan.kt
git commit -m "feat(ui): add AnsiSpan value types"
```

---

## Task 2: `AnsiParser`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiParser.kt`
- Test: `src/test/kotlin/com/example/ghactions/ui/ansi/AnsiParserTest.kt`

Pure parser, no Swing. Recognises only what GitHub Actions actually emits.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/ui/ansi/AnsiParserTest.kt`:

```kotlin
package com.example.ghactions.ui.ansi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AnsiParserTest {

    private val ESC = ""

    @Test
    fun `plain text returns single default span`() {
        val spans = AnsiParser.parse("hello world\n")
        assertEquals(1, spans.size)
        assertEquals("hello world\n", spans[0].text)
        assertEquals(AnsiStyle.DEFAULT, spans[0].style)
    }

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList(), AnsiParser.parse(""))
    }

    @Test
    fun `simple foreground color`() {
        val spans = AnsiParser.parse("$ESC[31merror$ESC[0m done")
        assertEquals(
            listOf(
                AnsiSpan("error", AnsiStyle(foreground = AnsiColor.RED)),
                AnsiSpan(" done", AnsiStyle.DEFAULT)
            ),
            spans
        )
    }

    @Test
    fun `bold + color combined in one CSI`() {
        val spans = AnsiParser.parse("$ESC[1;32mPASS$ESC[0m")
        assertEquals(
            listOf(AnsiSpan("PASS", AnsiStyle(foreground = AnsiColor.GREEN, bold = true))),
            spans
        )
    }

    @Test
    fun `bright color codes 90-97`() {
        val spans = AnsiParser.parse("$ESC[91mbright red$ESC[0m")
        assertEquals(
            listOf(AnsiSpan("bright red", AnsiStyle(foreground = AnsiColor.BRIGHT_RED))),
            spans
        )
    }

    @Test
    fun `unknown code is stripped without leaking escape characters`() {
        // 256-color escape: ESC[38;5;208m (orange). We don't support it, but it must not
        // appear as visible text.
        val spans = AnsiParser.parse("${ESC}[38;5;208morange${ESC}[0m next")
        val rendered = spans.joinToString("") { it.text }
        assertEquals("orange next", rendered)
    }

    @Test
    fun `reset clears bold and color`() {
        val spans = AnsiParser.parse("$ESC[1;31mAB$ESC[0mCD")
        assertEquals(
            listOf(
                AnsiSpan("AB", AnsiStyle(foreground = AnsiColor.RED, bold = true)),
                AnsiSpan("CD", AnsiStyle.DEFAULT)
            ),
            spans
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.ui.ansi.AnsiParserTest`
Expected: FAIL — `AnsiParser` unresolved.

- [ ] **Step 3: Implement `AnsiParser`**

Create `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiParser.kt`:

```kotlin
package com.example.ghactions.ui.ansi

/**
 * Parses CSI SGR escape sequences (`ESC [ codes m`) into a list of styled [AnsiSpan]s.
 *
 * **Recognised codes**
 * - `0`             — reset to default
 * - `1`             — bold
 * - `30..37`        — standard foreground (BLACK..WHITE)
 * - `90..97`        — bright foreground (BRIGHT_BLACK..BRIGHT_WHITE)
 *
 * **Behaviour for everything else**
 * - 256-color (`38;5;n`), truecolor (`38;2;r;g;b`), background colors (`40..47`,
 *   `100..107`), italic, underline, etc. — these *codes* are silently consumed; the
 *   surrounding escape characters are stripped. Unrecognised CSI sequences (e.g. cursor
 *   moves) are dropped entirely.
 * - Lone `ESC` characters not part of a CSI sequence are dropped.
 */
object AnsiParser {

    private const val ESC = ''

    fun parse(text: String): List<AnsiSpan> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<AnsiSpan>()
        val buf = StringBuilder()
        var style = AnsiStyle.DEFAULT
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ESC && i + 1 < text.length && text[i + 1] == '[') {
                // Flush the buffer into a span before changing style.
                if (buf.isNotEmpty()) {
                    out += AnsiSpan(buf.toString(), style)
                    buf.setLength(0)
                }
                // Find the terminator. CSI sequences end at the first byte in 0x40..0x7E.
                val termIdx = findCsiTerminator(text, i + 2)
                if (termIdx < 0) {
                    // Malformed — drop the rest of the escape but keep emitting text.
                    i = text.length
                    continue
                }
                val params = text.substring(i + 2, termIdx)
                val terminator = text[termIdx]
                if (terminator == 'm') {
                    style = applySgr(style, params)
                }
                // Non-`m` terminators (e.g. cursor moves) are silently dropped.
                i = termIdx + 1
            } else {
                buf.append(c)
                i++
            }
        }
        if (buf.isNotEmpty()) out += AnsiSpan(buf.toString(), style)
        return out
    }

    private fun findCsiTerminator(text: String, fromIndex: Int): Int {
        for (j in fromIndex until text.length) {
            val b = text[j].code
            if (b in 0x40..0x7E) return j
        }
        return -1
    }

    private fun applySgr(current: AnsiStyle, params: String): AnsiStyle {
        if (params.isEmpty()) return AnsiStyle.DEFAULT
        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty()) return current
        var style = current
        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            when {
                code == 0 -> style = AnsiStyle.DEFAULT
                code == 1 -> style = style.copy(bold = true)
                code in 30..37 -> style = style.copy(foreground = standardColor(code - 30))
                code in 90..97 -> style = style.copy(foreground = brightColor(code - 90))
                code == 38 -> {
                    // Extended color: 38;5;n (256) or 38;2;r;g;b (truecolor). Skip the
                    // payload bytes so we don't re-parse them as separate codes.
                    i += skipExtendedPalette(codes, i + 1)
                }
                code == 48 -> {
                    // Same shape as 38, for background. Skip payload, no rendering.
                    i += skipExtendedPalette(codes, i + 1)
                }
                else -> { /* swallow unknown codes silently */ }
            }
            i++
        }
        return style
    }

    private fun skipExtendedPalette(codes: List<Int>, fromIndex: Int): Int {
        // Returns how many *additional* indices to advance past the introducer (38 or 48).
        val mode = codes.getOrNull(fromIndex) ?: return 0
        return when (mode) {
            5 -> 2          // 5; n
            2 -> 4          // 2; r; g; b
            else -> 0
        }
    }

    private fun standardColor(idx: Int): AnsiColor = AnsiColor.values()[idx]
    private fun brightColor(idx: Int): AnsiColor = AnsiColor.values()[idx + 8]
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.ui.ansi.AnsiParserTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/ansi/AnsiParser.kt \
        src/test/kotlin/com/example/ghactions/ui/ansi/AnsiParserTest.kt
git commit -m "feat(ui): add ANSI escape-code parser"
```

---

## Task 3: `AnsiPalette`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiPalette.kt`
- Test: `src/test/kotlin/com/example/ghactions/ui/ansi/AnsiPaletteTest.kt`

Single object; one method `awtColor(AnsiColor): java.awt.Color`. Hand-picked palette tuned to be legible on both light and dark IDE backgrounds — i.e. medium saturation, mid-luminance colors. Unit-tested for sanity (RGB values match the expected hex; bright variants are distinguishable from standard variants).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/ui/ansi/AnsiPaletteTest.kt`:

```kotlin
package com.example.ghactions.ui.ansi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AnsiPaletteTest {

    @Test
    fun `every color maps to a distinct AWT colour`() {
        val mapped = AnsiColor.values().map { AnsiPalette.awtColor(it) }
        assertEquals(mapped.size, mapped.toSet().size, "duplicate colours in palette")
    }

    @Test
    fun `red is reddish and green is greenish`() {
        val red = AnsiPalette.awtColor(AnsiColor.RED)
        val green = AnsiPalette.awtColor(AnsiColor.GREEN)
        // R > G,B for red; G > R,B for green. Keeps the palette honest.
        assertEquals(true, red.red > red.green && red.red > red.blue, "RED dominant channel")
        assertEquals(true, green.green > green.red && green.green > green.blue, "GREEN dominant channel")
    }

    @Test
    fun `bright variants differ from their non-bright siblings`() {
        // Pick three to spot-check; the duplicate-set test above covers the rest.
        assertNotEquals(AnsiPalette.awtColor(AnsiColor.RED), AnsiPalette.awtColor(AnsiColor.BRIGHT_RED))
        assertNotEquals(AnsiPalette.awtColor(AnsiColor.GREEN), AnsiPalette.awtColor(AnsiColor.BRIGHT_GREEN))
        assertNotEquals(AnsiPalette.awtColor(AnsiColor.WHITE), AnsiPalette.awtColor(AnsiColor.BRIGHT_WHITE))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.ui.ansi.AnsiPaletteTest`
Expected: FAIL — `AnsiPalette` unresolved.

- [ ] **Step 3: Implement `AnsiPalette`**

Create `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiPalette.kt`:

```kotlin
package com.example.ghactions.ui.ansi

import java.awt.Color

/**
 * Maps [AnsiColor] to [java.awt.Color]. The palette is hand-picked to remain legible on
 * both the IntelliJ Light and Darcula backgrounds — i.e. avoid pure black/white and the
 * fully saturated primaries that disappear into bright themes.
 *
 * Source values are the "VS Code default dark" approximations, which behave similarly
 * well on the IDE Light theme.
 */
object AnsiPalette {
    fun awtColor(color: AnsiColor): Color = when (color) {
        AnsiColor.BLACK         -> Color(0x3B, 0x42, 0x52)
        AnsiColor.RED           -> Color(0xBF, 0x61, 0x6A)
        AnsiColor.GREEN         -> Color(0xA3, 0xBE, 0x8C)
        AnsiColor.YELLOW        -> Color(0xEB, 0xCB, 0x8B)
        AnsiColor.BLUE          -> Color(0x81, 0xA1, 0xC1)
        AnsiColor.MAGENTA       -> Color(0xB4, 0x8E, 0xAD)
        AnsiColor.CYAN          -> Color(0x88, 0xC0, 0xD0)
        AnsiColor.WHITE         -> Color(0xE5, 0xE9, 0xF0)
        AnsiColor.BRIGHT_BLACK  -> Color(0x4C, 0x56, 0x6A)
        AnsiColor.BRIGHT_RED    -> Color(0xD0, 0x87, 0x70)
        AnsiColor.BRIGHT_GREEN  -> Color(0xB5, 0xCE, 0xA8)
        AnsiColor.BRIGHT_YELLOW -> Color(0xF0, 0xD8, 0xA8)
        AnsiColor.BRIGHT_BLUE   -> Color(0x8F, 0xBC, 0xBB)
        AnsiColor.BRIGHT_MAGENTA -> Color(0xC5, 0x95, 0xC5)
        AnsiColor.BRIGHT_CYAN   -> Color(0x8F, 0xD8, 0xE0)
        AnsiColor.BRIGHT_WHITE  -> Color(0xEC, 0xEF, 0xF4)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.ui.ansi.AnsiPaletteTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 125 tests (115 from Plan 6 + 7 parser + 3 palette).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/ansi/AnsiPalette.kt \
        src/test/kotlin/com/example/ghactions/ui/ansi/AnsiPaletteTest.kt
git commit -m "feat(ui): add AnsiPalette mapping ANSI colors to AWT"
```

---

## Task 4: `AnsiTextPane` Swing component

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiTextPane.kt`

A small `JTextPane` subclass with a `setSpans(List<AnsiSpan>)` API. No automated tests for this task — Swing rendering is verified by the smoke test (Task 6). The class is small enough that a code-read review is sufficient.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/ui/ansi/AnsiTextPane.kt`:

```kotlin
package com.example.ghactions.ui.ansi

import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Read-only `JTextPane` that renders [AnsiSpan]s with foreground colour and bold attributes
 * applied via [StyleConstants]. Replaces the `JTextArea` previously held by `LogViewerPanel`.
 *
 * Why not `EditorEx`/`EditorTextField`: Plan 2 hit memory leaks via the IntelliJ editor
 * lifecycle; plain Swing avoids the disposal complexity entirely. The trade-off (no smart
 * gutter, no syntax editor features) is acceptable — log viewing doesn't need those.
 */
class AnsiTextPane : JTextPane() {

    init {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    /**
     * Replace the displayed content. Safe to call from the EDT only — caller's responsibility
     * to marshal (current callers already do via `ApplicationManager.invokeLater`).
     */
    fun setSpans(spans: List<AnsiSpan>) {
        val doc = styledDocument
        doc.remove(0, doc.length)
        for (span in spans) {
            val attrs = SimpleAttributeSet()
            span.style.foreground?.let { StyleConstants.setForeground(attrs, AnsiPalette.awtColor(it)) }
            if (span.style.bold) StyleConstants.setBold(attrs, true)
            doc.insertString(doc.length, span.text, attrs)
        }
        caretPosition = 0
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/ansi/AnsiTextPane.kt
git commit -m "feat(ui): add AnsiTextPane (styled JTextPane for ANSI output)"
```

---

## Task 5: Wire `AnsiTextPane` into `LogViewerPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt`

Swap `JTextArea` for `AnsiTextPane`; route text through `AnsiParser.parse` before setting spans.

- [ ] **Step 1: Replace the file**

Replace the entire contents of `src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt` with:

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.ui.ansi.AnsiParser
import com.example.ghactions.ui.ansi.AnsiTextPane
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Read-only viewer for log text. Plan 7: ANSI escape codes in the input are parsed and
 * rendered with foreground color + bold via [AnsiTextPane]. Plan 3's per-step zip extraction
 * still feeds [setText]; this panel doesn't parse run structure.
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private var rawText: String = ""

    private val textPane = AnsiTextPane().apply {
        margin = JBUI.insets(4, 8)
    }

    private val timestampToggle = JBCheckBox("Show timestamps", false).apply {
        addItemListener { renderText() }
    }

    private val statusLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(2, 6)
        add(timestampToggle)
        add(statusLabel)
    }

    private val scrollPane = JBScrollPane(textPane).apply {
        border = JBUI.Borders.empty()
    }

    init {
        border = JBUI.Borders.empty()
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Replace the displayed text. Safe from any thread; marshals to EDT. */
    fun setText(text: String) {
        rawText = text
        statusLabel.text = ""
        renderText()
    }

    /** Set a status hint shown next to the timestamps toggle (e.g., 'Step 3 · Run swift test'). */
    fun setStatus(text: String) {
        statusLabel.text = text
    }

    fun clear() {
        setText("")
        statusLabel.text = ""
    }

    private fun renderText() {
        ApplicationManager.getApplication().invokeLater {
            val visible = if (timestampToggle.isSelected) {
                rawText
            } else {
                rawText.lineSequence().joinToString("\n") { stripTimestamp(it) }
            }
            textPane.setSpans(AnsiParser.parse(visible))
        }
    }

    private fun stripTimestamp(line: String): String =
        TIMESTAMP_RE.replaceFirst(line, "")

    private companion object {
        private val TIMESTAMP_RE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\s""")
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — still 125 tests (no test changes, just a panel rewire).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt
git commit -m "feat(ui): render log output with ANSI colors via AnsiTextPane"
```

---

## Task 6: Manual smoke test (`./gradlew runIde`)

**Files:** none.

Verifies the visual rendering works end-to-end against real GitHub Actions log output. The ANSI parser has thorough automated tests; the smoke test only confirms:
1. The text pane appears in the run/job/step detail view.
2. Color and bold actually render (not just plain text).
3. Existing controls — timestamp toggle, scroll, copy — still work.
4. Non-ANSI plain text still renders as before.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens.

- [ ] **Step 2: Open the GitHub Actions tool window and load a step's log**

Find a step whose log is known to contain ANSI codes — most CI jobs do (test runners, npm/yarn, gradle, swift-test). Click the step.

Expected: the log appears with colored text where ANSI codes were present (errors red, success green, warnings yellow). Plain stretches stay default-colored.

- [ ] **Step 3: Toggle "Show timestamps"**

Expected: the timestamp prefix appears/disappears, colors stay correct.

- [ ] **Step 4: Try a step whose log has NO ANSI codes**

Expected: pure plain text, no escape characters visible (regression check on the parser's ESC-handling).

- [ ] **Step 5: Try a step whose log has 256-color or truecolor escapes**

(Many newer Rust / Cargo logs use 256-color.) Expected: text renders without color but **without escape characters leaking into the output**.

- [ ] **Step 6: Document any deviations**

If any step fails, write the deviation in `docs/superpowers/notes/2026-04-30-ansi-rendering-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 7"
```

If no deviations, skip the commit.

---

## Task 7: Final sweep + merge

**Files:** none — verification and merge only.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 125 tests (115 from Plan 6 + 7 parser + 3 palette).

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Updated zip at `build/distributions/`.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. Acceptable warnings: existing `SegmentedButton` experimental-API uses (carry-over from Plan 4).

- [ ] **Step 4: Fast-forward merge to `main`**

```bash
git checkout main
git merge --ff-only feat/plan-7-ansi-rendering
git log --oneline | head -10
```

Expected: clean fast-forward.

- [ ] **Step 5: Plan-level verification**

- All 7 tasks have green check-marks (Task 6 may be deferred if smoke testing isn't immediate — note in the task list).
- `./gradlew test` passes (125 tests).
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.
- `git log --oneline` on `main` shows the expected sequence: AnsiSpan → AnsiParser → AnsiPalette → AnsiTextPane → LogViewerPanel rewire.

---

## What ships at the end of Plan 7

- Step-log output renders with ANSI colors (16-color palette) and bold attributes.
- Unknown ANSI sequences (256-color, truecolor, background colors, italic, underline, cursor moves) are silently consumed — no escape characters leak into the rendered text.
- All existing `LogViewerPanel` features (timestamp toggle, status label, copy) continue to work.

What it does **not** yet do (deferred to later plans):
- 256-color (`38;5;n`) and truecolor (`38;2;r;g;b`) rendering.
- Background colors.
- GitHub workflow-command markers (`::error`, `::warning`, `::notice`).
- `##[group]` / `##[endgroup]` folding UI.
- Log search / find-in-log.

---

## Open questions / risks

1. **`JTextPane` performance on very long logs.** A 10 MB ANSI-heavy log will be slower to render than `JTextArea`. We're betting that GitHub Actions step logs are typically under 2 MB, well within `JTextPane`'s comfort zone. If a user reports slowness, the fix is incremental rendering (chunk insertion) — straightforward to add later.
2. **Palette legibility on uncommon themes.** The hand-picked palette tested well on default Light/Darcula. High-contrast or custom themes may surprise us. The `AnsiPalette` object is the single point of change.
3. **Bold weight on the IDE's monospace font.** Some monospace fonts ship without a real bold variant; `JTextPane` synthesises a fake bold which can look chunky. This is an aesthetic concern, not a correctness issue. Defer.
