# Performance Investigation: JCEF Freezes with Long Contexts

**Date:** 2026-05-25
**Symptom:** When conversation context reaches ~7000K characters, the JCEF browser window becomes unresponsive — unable to click buttons or type in the input field.
**Affected Component:** webview (React + JCEF), Java backend (StreamMessageCoalescer)

---

## Root Cause Analysis

### 1. DOM Bloat — No Virtualization, No Content Truncation

**File:** `webview/src/components/MessageList.tsx:12`

```ts
const VISIBLE_MESSAGE_WINDOW = 15;
```

This constant only limits the **number of message entries** rendered, not the DOM size within each message. A single Claude assistant response can contain:

- Large Markdown text blocks (with syntax-highlighted code blocks via highlight.js)
- Multiple tool calls (Edit/Bash/Read), each with full output rendered in the DOM
- Thinking blocks (extended thinking content also rendered via MarkdownBlock)

When context reaches ~7000K characters, even 15 visible messages can produce hundreds of thousands of DOM nodes.

**Evidence:**
- `MarkdownBlock.tsx:464-526` — uses `dangerouslySetInnerHTML` for the entire message content, producing large DOM trees on every render
- `BashToolBlock.tsx:67` — `output` is rendered as `<span>{output}</span>` with **no length limit**; a long-running command can produce tens of thousands of lines
- `EditToolBlock.tsx:32-89` — `computeDiff()` renders **all diff lines** as individual DOM nodes with no truncation

### 2. Dual Update Channels During Streaming — Excessive setMessages Calls

During streaming, **two independent update channels** run simultaneously:

**Channel A — Java Backend (StreamMessageCoalescer):**
- File: `src/main/java/.../StreamMessageCoalescer.java:22`
- Sends full `updateMessages()` every 50ms
- Each call: full JSON serialization → `JCEF callJavaScript` → full message list replacement

**Channel B — Frontend Delta (streamingCallbacks.ts):**
- File: `webview/src/hooks/windowCallbacks/registerCallbacks/streamingCallbacks.ts:116-177`
- `onContentDelta` updates every 50ms (THROTTLE_INTERVAL)
- Each call: `setMessages(prev => [...prev])` — creates new array copy

**Combined effect:** ~40 `setMessages()` calls per second, each triggering:

1. `useMessageProcessing.mergedMessages` recalculation (`useMessageProcessing.ts:105-113`)
2. `MessageList` re-render with new `messages` prop
3. `visibleMessages` useMemo recalculation
4. React reconciliation of all visible `MessageItem` components
5. Full `MarkdownBlock` re-render for the streaming message

**Key code paths:**
```
streamingCallbacks.ts: onContentDelta (every 50ms)
  → setMessages(prev => [...prev])
  → useMessageProcessing.mergedMessages (useMemo, deps: [messages])
  → MessageList re-render (memo, but messages prop changed)
  → visibleMessages useMemo recalc
  → MessageItem reconciliation for all 15 visible messages
  → MarkdownBlock html useMemo recalc for streaming message
```

### 3. MarkdownBlock — Heavy Rendering Pipeline

**File:** `webview/src/components/MarkdownBlock.tsx`

The `html` useMemo (line 464) runs a heavy pipeline on every content change:

- **Streaming mode:** `renderStreamingContent()` — lightweight but still full innerHTML replacement
- **Non-streaming mode:** `marked.parse()` → `DOMPurify.sanitize()` → `DOMParser.parseFromString()` → DOM manipulation (wrapping `<pre>` in `.code-block-wrapper` with copy buttons)
- **Syntax highlighting:** highlight.js runs on every code block (30+ registered languages)

When streaming ends (line 529-562), an additional `useEffect` runs: double `requestAnimationFrame` + `setTimeout` fallback to force DOM refresh, plus mermaid diagram rendering.

### 4. EditToolBlock — Unbounded O(m×n) Diff Computation

**File:** `webview/src/components/EditToolBlock.tsx:32-89`

`computeDiff()` uses classic LCS dynamic programming (O(m×n) time and space). For a 1000-line file edit:
- 1,000,000 cell DP table computation
- All diff lines rendered as individual DOM nodes (line 322-388)
- Each line has 3 nested divs + 1 pre element

### 5. JCEF-Specific Overhead

**File:** `StreamMessageCoalescer.java:270-275`

```java
JBCefBrowser repaintBrowser = callbackTarget.getBrowser();
if (repaintBrowser != null && !callbackTarget.isDisposed()) {
    java.awt.Component component = repaintBrowser.getComponent();
    if (component != null) {
        component.repaint();
    }
}
```

Forced `component.repaint()` after every update cycle (every 50ms during streaming). JCEF on Windows has inherently lower performance than desktop Chrome for DOM-heavy operations.

---

## Existing Mitigations (Not Sufficient)

| Mitigation | Location | Why Insufficient |
|-----------|----------|-----------------|
| `VISIBLE_MESSAGE_WINDOW = 15` | MessageList.tsx:12 | Only limits message count, not per-message DOM size |
| `THROTTLE_INTERVAL = 50ms` | useStreamingMessages.ts:4 | Limits to 20 delta updates/sec, but backend also sends 20/sec |
| `memo()` on components | MessageItem, MessageList, MarkdownBlock | Prevents re-renders of unchanged messages, but streaming message still re-renders fully |
| `WeakMap` caches | useMessageProcessing.ts:28-29 | Avoids re-parsing unchanged messages, but doesn't help with DOM size |
| `renderStreamingContent()` | MarkdownBlock.tsx:213-299 | Lightweight during streaming, but full pipeline runs when streaming ends |
| CollapsibleTextBlock | CollapsibleTextBlock.tsx | Only applies to user messages (height-limited), not assistant messages or tool outputs |

---

## Proposed Solutions

### P0 — High Impact, Low-Medium Effort

#### P0-1: Disable Backend updateMessages During Delta Streaming

**Problem:** Both backend and frontend send message updates every 50ms during streaming, doubling state updates.

**Proposal:** When the frontend has entered delta mode (`useBackendStreamingRenderRef.current === false`), the Java backend should skip sending full `updateMessages` and only push when tool_use blocks change. The backend already tracks `toolUseCount` — gate the coalesced push on `toolUseCount` changes.

**Expected Impact:** ~50% reduction in `setMessages()` calls during streaming.

**Risk:** If the delta channel fails (e.g., onContentDelta not called), the UI won't receive updates. Current fallback: Codex mode stays in backend streaming mode (`useBackendStreamingRenderRef = true`), so this only affects Claude mode where deltas are guaranteed.

**Files to modify:**
- `StreamMessageCoalescer.java` — add tool-use-only gate
- Or: `streamingCallbacks.ts` — make frontend ignore backend updates more aggressively when in delta mode

#### P0-2: BashToolBlock Output Truncation

**Problem:** `BashToolBlock` and `BashToolGroupBlock` render full command output with no line limit.

**Proposal:** Add a `MAX_OUTPUT_LINES = 500` constant. When output exceeds this, only render the first 500 lines + last 50 lines, with a "Show all N lines" expand button in between.

**Expected Impact:** For long-running commands with 10K+ lines of output, DOM nodes drop from ~30K+ to ~1650.

**Risk:** Users lose the ability to see full output inline. Mitigation: "Show all" button restores full output, or copy-to-clipboard provides the full text.

**Files to modify:**
- `webview/src/components/toolBlocks/BashToolBlock.tsx`
- `webview/src/components/toolBlocks/BashToolGroupBlock.tsx`

### P1 — Medium Impact, Medium Effort

#### P1-1: Lazy Rendering with IntersectionObserver

**Problem:** All 15 visible messages render their full Markdown/tool content eagerly, even if scrolled off-screen.

**Proposal:** Use `IntersectionObserver` to detect which messages are actually in the viewport. For messages outside the viewport, render a lightweight placeholder (height estimate) instead of full MarkdownBlock.

**Expected Impact:** Reduces active DOM nodes to ~3-5 messages at any time.

**Risk:** Scroll jank if placeholder height estimates are wrong. Mitigation: measure actual heights and cache them.

**Files to modify:**
- `webview/src/components/MessageList.tsx`
- New component: `LazyMessageItem`

#### P1-2: EditToolBlock Diff Line Truncation

**Problem:** Large file edits render all diff lines as DOM nodes.

**Proposal:** When diff exceeds 500 lines, render only: context around changed lines (±3 lines), with a collapsed "N unchanged lines" indicator in between.

**Expected Impact:** For a 1000-line diff with 50 changes, DOM nodes drop from ~4000 to ~500.

**Risk:** Loss of full diff visibility. Mitigation: "Expand" button per section, or the existing "Show Diff in IDEA" button.

**Files to modify:**
- `webview/src/components/toolBlocks/EditToolBlock.tsx`

### P2 — High Impact, High Effort

#### P2-1: Virtual Scrolling (react-window or custom)

**Problem:** No virtualization — all visible messages are in the DOM.

**Proposal:** Implement virtual scrolling so only messages in the viewport (+ buffer) are mounted. Use fixed/estimated row heights.

**Expected Impact:** DOM size stays constant regardless of conversation length.

**Risk:** High implementation complexity. Variable-height rows (Markdown content) make virtual scrolling difficult. JCEF may have quirks with `IntersectionObserver` or scroll measurement.

**Files to modify:**
- `webview/src/components/MessageList.tsx`
- Potentially new hooks for virtual scroll management

#### P2-2: Backend Incremental Updates

**Problem:** `StreamMessageCoalescer` sends the **entire** message list as JSON every 50ms.

**Proposal:** Send incremental updates (only the changed message or delta) instead of the full list. The frontend would apply patches rather than replace the entire array.

**Expected Impact:** Reduces JSON serialization time, JS parsing time, and GC pressure from array copying.

**Risk:** Complex protocol change. Race conditions between delta and full updates. Requires careful sequencing.

**Files to modify:**
- `StreamMessageCoalescer.java`
- `webview/src/hooks/windowCallbacks/registerCallbacks/messageCallbacks.ts`
- `webview/src/hooks/windowCallbacks/registerCallbacks/streamingCallbacks.ts`

---

## Quick Wins (Can Ship Immediately)

1. **Increase `THROTTLE_INTERVAL` from 50ms to 100ms** — `useStreamingMessages.ts:4` — halves frontend delta updates with minimal visual impact
2. **Debounce non-streaming MarkdownBlock renders** — Add a short debounce to the `html` useMemo to avoid recalculating during rapid message updates
3. **Add `will-change: transform` to message items** — CSS hint for GPU compositing, helps JCEF rendering

---

## Metrics to Verify

Before and after fixes, measure:
1. **DOM node count** — `document.querySelectorAll('*').length` in the JCEF console
2. **Main thread time** — `performance.measure()` around setMessages calls
3. **Memory usage** — `performance.memory?.usedJSHeapSize`
4. **User-perceived lag** — time from keystroke to character appearing in input box
