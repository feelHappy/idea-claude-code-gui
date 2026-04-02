package com.github.claudecodegui.session;

import com.github.claudecodegui.util.JsUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.Alarm;

/**
 * Coalesces rapid streaming delta events (content / thinking) to throttle
 * webview pushes. Instead of firing one executeJavaScript call per token,
 * this batches deltas into periodic pushes at a configurable interval.
 *
 * <p>Thread-safety: append() may be called from any thread;
 * the flushed JavaScript call is always dispatched via invokeLater (EDT).</p>
 */
public class DeltaCoalescer {

    private static final Logger LOG = Logger.getInstance(DeltaCoalescer.class);
    private static final int DEFAULT_INTERVAL_MS = 50;

    private final Object lock = new Object();
    private final Alarm flushAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
    private final String jsFunctionName;
    private final SessionCallbackAdapter.JsTarget jsTarget;
    private final int intervalMs;

    private StringBuilder pendingDelta = null;
    private volatile boolean flushScheduled = false;

    /**
     * @param jsFunctionName the JavaScript function to call (e.g. "onContentDelta")
     * @param jsTarget       the callback target for pushing to the webview
     */
    public DeltaCoalescer(String jsFunctionName, SessionCallbackAdapter.JsTarget jsTarget) {
        this(jsFunctionName, jsTarget, DEFAULT_INTERVAL_MS);
    }

    /**
     * @param jsFunctionName the JavaScript function to call
     * @param jsTarget       the callback target for pushing to the webview
     * @param intervalMs     coalescing interval in milliseconds
     */
    public DeltaCoalescer(String jsFunctionName, SessionCallbackAdapter.JsTarget jsTarget, int intervalMs) {
        this.jsFunctionName = jsFunctionName;
        this.jsTarget = jsTarget;
        this.intervalMs = intervalMs;
    }

    /**
     * Append a delta fragment. The fragment will be merged with any pending
     * deltas and delivered to the webview within {@code intervalMs}.
     */
    public void append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (pendingDelta == null) {
                pendingDelta = new StringBuilder(delta);
            } else {
                pendingDelta.append(delta);
            }
        }
        scheduleFlush();
    }

    /**
     * Flush any pending delta immediately (e.g. on stream end).
     */
    public void flush() {
        final String merged;
        synchronized (lock) {
            flushAlarm.cancelAllRequests();
            flushScheduled = false;
            merged = drainLocked();
        }
        if (merged != null) {
            pushToWebView(merged);
        }
    }

    /**
     * Reset all state (e.g. on new session creation).
     */
    public void reset() {
        synchronized (lock) {
            flushAlarm.cancelAllRequests();
            flushScheduled = false;
            pendingDelta = null;
        }
    }

    /**
     * Dispose internal resources.
     */
    public void dispose() {
        try {
            flushAlarm.cancelAllRequests();
            flushAlarm.dispose();
        } catch (Exception e) {
            LOG.warn("Failed to dispose delta coalescer alarm: " + e.getMessage());
        }
    }

    // ---- internals ----

    private void scheduleFlush() {
        synchronized (lock) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }

        flushAlarm.addRequest(() -> {
            final String merged;
            synchronized (lock) {
                flushScheduled = false;
                merged = drainLocked();
            }
            if (merged != null) {
                pushToWebView(merged);
            }
        }, intervalMs);
    }

    /**
     * Drain the pending buffer under the lock and return the merged string,
     * or null if nothing was pending.
     */
    private String drainLocked() {
        if (pendingDelta == null || pendingDelta.length() == 0) {
            pendingDelta = null;
            return null;
        }
        String result = pendingDelta.toString();
        pendingDelta = null;
        return result;
    }

    private void pushToWebView(String merged) {
        ApplicationManager.getApplication().invokeLater(() -> {
            jsTarget.callJavaScript(jsFunctionName, JsUtils.escapeJs(merged));
        });
    }
}
